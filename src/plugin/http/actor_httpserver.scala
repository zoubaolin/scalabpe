package jvmdbbroker.plugin.http

import java.io._
import org.apache.commons.io.FileUtils
import org.apache.commons.lang.StringUtils
import java.util.{Date,Timer,TimerTask,Calendar,GregorianCalendar,Locale,TimeZone}
import java.util.concurrent.atomic._
import java.util.concurrent._
import java.util.concurrent.locks.ReentrantLock;
import java.text.SimpleDateFormat
import java.nio.charset.Charset
import java.net._
import org.jboss.netty.handler.codec.http._;
import org.jboss.netty.buffer._;
import org.jboss.netty.channel._;
import scala.collection.mutable.{ArrayBuffer,HashMap,SynchronizedQueue,HashSet}
import scala.xml._
import scala.util.matching.Regex
import java.text.{SimpleDateFormat,ParsePosition}

import jvmdbbroker.core._

object TimeHelper {

    val FORMAT_TYPE_TIMESTAMP = "yyyy-MM-dd HH:mm:ss"

    def convertTimestamp(s:String):Date = {
        if (s == null || s == "" ) {
            return null;
        }

        var str = s
        if (str.length() == 10) {
            str += " 00:00:00";
        }

        return new SimpleDateFormat(FORMAT_TYPE_TIMESTAMP).parse(str, new ParsePosition(0));
    }

    def convertTimestamp(d:Date):String = {
        if (d == null) {
            return null;
        }
        return new SimpleDateFormat(FORMAT_TYPE_TIMESTAMP).format(d);
    }

}

class HttpServerCacheData(val req:HttpSosRequest, val timer: QuickTimer) {
    val sendTime = System.currentTimeMillis
}

class HttpServerRequest(val httpReq:HttpRequest,val connId:String)
class HttpServerRequestTimeout(val requestId:String)

class OsapKeyData(val key:String,val st:Long,val et:Long)

class OsapMerchantCfg(val merchantName:String,val appId:Int,val areaId:Int,
    val md5Key:ArrayBuffer[OsapKeyData] = ArrayBuffer[OsapKeyData](),
    val ipSet:HashSet[String] = HashSet[String]() ,
    val privilegeSet:HashSet[String] = HashSet[String]() )

class OsapRefreshData {
  var merchants:InvokeResult = _
  var keys:InvokeResult = _
  var ips:InvokeResult = _
  var privileges:InvokeResult = _

  def allReplied() = merchants != null && keys != null && ips != null && privileges != null
}

class RpcInfo(val requestId:String,val id:String, val uri:String,val bodyParams:HashMapStringAny,
    var reqRes:HttpSosRequestResponseInfo = null,
    var cookies: HashMap[String,Cookie]  = null,
    var headers: HashMap[String,String]  = null)

class RpcCall(val buff:ArrayBuffer[RpcInfo]) {

  def updateResult(tpl: Tuple3[HttpSosRequestResponseInfo,HashMap[String,Cookie],HashMap[String,String]]):Unit = {
    val (reqRes,cookies,headers) = tpl
    for( info <- buff if info.requestId == reqRes.req.requestId ) {
      info.reqRes = reqRes
      info.cookies = cookies
      info.headers = headers
    }
  }

  def finished():Boolean = {
    !buff.exists( _.reqRes == null )
  }

}


class HttpServerActor(val router: Router,val cfgNode: Node) extends Actor
    with BeforeClose  with Refreshable with AfterInit with HttpServer4Netty with Logging with Dumpable with Closable {

    type ArrayBufferPattern = ArrayBuffer[Tuple2[Int,String]];

    val MIMETYPE_FORM = "application/x-www-form-urlencoded"
    val MIMETYPE_JSON = "application/json"
    val MIMETYPE_JAVASCRIPT = "text/javascript"
    val MIMETYPE_XML = "text/xml"
    val MIMETYPE_XML2 = "application/xml"
    val MIMETYPE_HTML = "text/html"
    val MIMETYPE_PLAIN = "text/plain"
    val MIMETYPE_DEFAULT = "application/octet-stream"
	val MIMETYPE_MULTIPART = "multipart/form-data"
	
    val errorFormat = """{"return_code":%d,"return_message":"%s","data":{}}"""
    val rpcFormat = """{"jsonrpc":"2.0","id":"%s","result":%s}"""

    val HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    val HTTP_DATE_GMT_TIMEZONE = "GMT";

    val df_tl = new ThreadLocal[SimpleDateFormat]() {
                 override def initialValue() : SimpleDateFormat = {
                     val df = new SimpleDateFormat(HTTP_DATE_FORMAT,Locale.US)
                     df.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
                     df
             }
          }

    var threadNum = 4
    val queueSize = 20000

    var host:String = "*"
    var port:Int = 0
    var timeout = 30000
    var idleTimeout = 45000
    var timerInterval:Int = 50
    var returnMessageFieldNames = ArrayBufferString("return_message","resultMsg","failReason","resultMessage","result_msg","fail_reason","result_message")
    var defaultVerify = "false"
    var osapDb = false
    var osapDbServiceId = 62100
    var sessionFieldName = "jsessionId"
    var sessionCookieName = "JSESSIONID"
    var sessionMode = 1 // 1=auto 2=manual
    var sessionIpBind = false
    var sessionHttpOnly = true
    var jsonRpcUrl = "/jsonrpc"
    var jsonpName = "jsonp"
    var logUserAgent = false
    var redirect302FieldName = "redirectUrl302"
    var requestUriFieldName = "requestUri"
    var queryStringFieldName = "queryString"
    var contentTypeFieldName = "contentType"
    var contentDataFieldName = "contentData"
	var maxContentLength = 5000000
    var sessionEncKey = CryptHelper.toHexString("9*cbd35w".getBytes());
	
    val classexMap = new HashMap[String,HashMap[String,String]]()
    val urlMapping = new HashMap[String,Tuple4[Int,Int,String,ArrayBufferPattern]]
    val msgAttrs = new HashMap[String,String]
    val whiteIpSet = new HashMap[String,HashSet[String]]
    val msgPlugins = new HashMap[String,HttpServerPlugin]
    val cookieMap = new HashMap[String,ArrayBuffer[Tuple3[String,String,String]]]()
    val headerMap = new HashMap[String,ArrayBuffer[Tuple2[String,String]]]()
    val logFilterMap = new HashMap[String,ArrayBuffer[Regex]]()

    val webappDir = Flow.router.rootDir+File.separator+"webapp"
    val webappDirExisted = new File(webappDir).exists()
    val webappStaticDir = webappDir+File.separator+"static"
    val webappStaticDirExisted = new File(webappStaticDir).exists()
    val webappUploadDir = webappDir+File.separator+"upload"
	
    var defaultMimeTypes =
            """
            text/html html htm
            text/plain txt text
            text/css css
            text/javascript js
            text/xml xml
            application/json json
            image/gif gif
            image/jpeg jpeg jpg jpe
            image/tiff tiff tif
            image/png png
            image/x-icon ico
            """
    var mimeTypeMap = new HashMapStringString

    var devMode = false
    var cacheEnabled = true
    var cacheFiles = "html htm css js"
    var cacheFileSize = 25000
    var cacheFilesSet = new HashSet[String]
    val cacheMap = new ConcurrentHashMap[String,Array[Byte]]()
    var httpCacheSeconds = 24*3600
    var skipMinFile = false
    var enableMock = false
    var urlArgs = "?"
    var hasPattern = false

    var codecs: TlvCodecs = _
    val localIp = IpUtils.localIp()
    var threadFactory : ThreadFactory = _
    var pool : ThreadPoolExecutor = _
    var qte : QuickTimerEngine = null

    val merchantMap = new AtomicReference[HashMap[String,OsapMerchantCfg]]()
    var osapRefreshData = new AtomicReference[OsapRefreshData]()

    var nettyHttpServer : NettyHttpServer = _

    val sequence = new AtomicInteger(1)
    val listened = new AtomicBoolean(false)
    val dataMap = new ConcurrentHashMap[String,HttpServerCacheData]()
    val rpcMap = new ConcurrentHashMap[String,RpcCall]()

    var timer: Timer = _

    init

    def init() {

        codecs = router.codecs
        merchantMap.set( new HashMap[String,OsapMerchantCfg]() )

        var s = (cfgNode \ "@threadNum").text
        if( s != "" ) threadNum = s.toInt

        s = (cfgNode \ "@host").text
        if( s != "" ) host = s

        s = (cfgNode \ "@port").text
        if( s != "" ) port = s.toInt

        s = (cfgNode \ "@timeout").text
        if( s != "" ) timeout = s.toInt

        s = (cfgNode \ "@idleTimeout").text
        if( s != "" ) idleTimeout = s.toInt

        s = (cfgNode \ "@timerInterval").text
        if( s != "" ) timerInterval = s.toInt

        s = (cfgNode \ "@defaultVerify").text
        if( s != "" ) defaultVerify = s

        s = (cfgNode \ "@osapDb").text
        if( s != "" ) osapDb = isTrue(s)

        s = (cfgNode \ "@osapDbServiceId").text
        if( s != "" ) osapDbServiceId = s.toInt

        s = (cfgNode \ "@returnMessageFieldNames").text
        if( s != "" ) {
            returnMessageFieldNames = new ArrayBufferString()
            val ss = s.split(",")
            for(s <- ss ) returnMessageFieldNames += s
        }

        s = (cfgNode \ "@sessionFieldName").text
        if( s != "" ) sessionFieldName = s

        s = (cfgNode \ "@sessionCookieName").text
        if( s != "" ) sessionCookieName = s

        s = (cfgNode \ "@sessionMode").text
        if( s != "" ) sessionMode = s.toInt

        s = (cfgNode \ "@sessionIpBind").text
        if( s != "" ) sessionIpBind = isTrue(s)

        s = (cfgNode \ "@sessionHttpOnly").text
        if( s != "" ) sessionHttpOnly = isTrue(s)

        s = (cfgNode \ "@sessionEncKey").text
        if( s != "" ) sessionEncKey = CryptHelper.toHexString(s.getBytes());

        s = (cfgNode \ "@jsonRpcUrl").text
        if( s != "" ) jsonRpcUrl = s

        s = (cfgNode \ "@maxContentLength").text
        if( s != "" ) maxContentLength = s.toInt
		
        val mimeItemlist = (cfgNode \ "MimeTypes" \ "Item").toList
        for( item <- mimeItemlist ) {
            var s = item.text.toString.trim
            defaultMimeTypes += "\n" + s
        }
        val types = defaultMimeTypes.split("\n").map(_.trim).filter(_!="")
        for(s <- types) {
            val ss = s.split(" ")
            if( ss.length >= 2 ) {
                val tp = ss(0)
                for(i <- 1 until ss.length )
                    mimeTypeMap.put(ss(i),tp)
            }
        }
        log.debug("http mimetypes:\n"+mimeTypeMap.mkString("\n"))

        s = (cfgNode \ "@cacheEnabled").text
        if( s != "" )  cacheEnabled = isTrue(s)

        s = (cfgNode \ "@cacheFileSize").text
        if( s != "" )  cacheFileSize = s.toInt

        s = (cfgNode \ "@cacheFiles").text
        if( s != "" ) { cacheFiles += " " + s }
        cacheFiles.split(" ").filter(_!="").foreach(cacheFilesSet.add(_))
        log.debug("http cache files:"+cacheFilesSet.mkString(","))

        s = (cfgNode \ "@httpCacheSeconds").text
        if( s != "" )  httpCacheSeconds = s.toInt

        s = (cfgNode \ "@httpTemplateCache").text
        if( s != "" )
            router.parameters.put("httpserver.template.cache",s) // used by plugin

        s = (cfgNode \ "@httpTemplateCheckInterval").text
        if( s != "" )
            router.parameters.put("httpserver.template.checkInterval",s) // used by plugin

        s = (cfgNode \ "@urlArgs").text
        if( s != "" )  urlArgs = s

        s = (cfgNode \ "@skipMinFile").text
        if( s != "" )  skipMinFile = isTrue(s)

        s = (cfgNode \ "@enableMock").text
        if( s != "" )  enableMock = isTrue(s)

        s = (cfgNode \ "@devMode").text
        if( s != "" )  devMode = isTrue(s)

        s = (cfgNode \ "@logUserAgent").text
        if( s != "" )  logUserAgent = isTrue(s)

        if( devMode ) {
            cacheEnabled = false
            httpCacheSeconds = 0
            router.parameters.put("httpserver.template.cache","0")
            skipMinFile = true
            enableMock = true
        }

        val itemlist = (cfgNode \ "UrlMapping" \ "Item").toList
        for( item <- itemlist ) {
            var s = item.text.toString.trim
            val ss = s.split(",")

            var url = ss(0).trim.toLowerCase
            if( url.startsWith("/") ) url = url.substring(1)
            val serviceId = ss(1).toInt
            val msgId = ss(2).toInt
            var pattern = new ArrayBufferPattern()
            val oldUrl = url
            if( url.indexOf(":") >= 0 ) { // restful parameters
                val ss = url.split("/")
                for(i <- 0 until ss.length) {
                    if( ss(i).startsWith(":") && ss(i).length >= 2 ) {
                        pattern += new Tuple2(i,ss(i).substring(1))
                        ss(i) = "*"
                        hasPattern = true
                    }
                }
                url = ss.mkString("/")
            }
            urlMapping.put(url,(serviceId,msgId,oldUrl,pattern) )

            s = ( item \ "@charset" ).text
            if( s != "" )
                msgAttrs.put(serviceId+"-"+msgId+"-charset",s)
            s = ( item \ "@requestContentType" ).text
            if( s != "" )
                msgAttrs.put(serviceId+"-"+msgId+"-requestContentType",s)
            s = ( item \ "@responseContentType" ).text
            if( s != "" )
                msgAttrs.put(serviceId+"-"+msgId+"-responseContentType",s)
            s = ( item \ "@caseInsensitive" ).text
            if( s != "" )
                msgAttrs.put(serviceId+"-"+msgId+"-caseInsensitive",s)
            s = ( item \ "@verify" ).text
            if( s != "" )
                msgAttrs.put(serviceId+"-"+msgId+"-verify",s)

            s = ( item \ "@bodyOnly" ).text
            if( s != "" )
                msgAttrs.put(serviceId+"-"+msgId+"-bodyOnly",s)

            s = ( item \ "@encodeRequest" ).text
            if( s != "" )
                msgAttrs.put(serviceId+"-"+msgId+"-encodeRequest",s)

            s = ( item \ "@whiteIps" ).text
            if( s != "" ) {
              val set = HashSet[String]()
              val ss = s.split(",")
              for( ts <- ss ) {
                   val ipgrp = router.getConfig(ts)
                   if( ipgrp != "") {
                          val ss2 = ipgrp.split(",")
                          for( ts2 <- ss2 ) set.add(ts2)
                   } else {
                     set.add(ts)
                   }
              }
              whiteIpSet.put(serviceId+"-"+msgId,set)
            }

            s = ( item \ "@logFilters" ).text
            if( s != "" ) {
              val buff = ArrayBuffer[Regex]()
              val ss = s.split(",")
              for( ts <- ss ) {
                   val grp = router.getConfig(ts)
                   if( grp != "") {
                     buff += grp.r
                   } else {
                     buff += ts.r
                   }
              }
              logFilterMap.put(serviceId+"-"+msgId,buff)
            }

            s = ( item \ "@plugin" ).text
            if( s != "" ) {

                var pluginParam = ""
                val p = s.indexOf(":")
                if( p >= 0 ) {
                    pluginParam = s.substring(p+1)
                    s = s.substring(0,p)
                }

                if(s == "plain") s = "jvmdbbroker.plugin.http.PlainTextPlugin"
                if(s == "redirect") s = "jvmdbbroker.plugin.http.RedirectPlugin"
                if(s == "template") s = "jvmdbbroker.plugin.http.TemplatePlugin"

                if( s.indexOf(".") < 0 ) s = "jvmdbbroker.flow."+s

                msgAttrs.put(serviceId+"-"+msgId+"-plugin",s)
                if( pluginParam != "")
                    msgAttrs.put(serviceId+"-"+msgId+"-pluginParam",pluginParam)

                var plugin = s
                try {

                  val obj = Class.forName(plugin).getConstructors()(0).newInstance()
                  if( !obj.isInstanceOf[HttpServerPlugin] ) {
                    throw new RuntimeException("plugin %s is not HttpServerPlugin".format(plugin))
                  }
                  val pluginObj = obj.asInstanceOf[HttpServerPlugin]
                  msgPlugins.put(serviceId+"-"+msgId,pluginObj)

                } catch {
                  case e:Exception =>
                    log.error("plugin {} cannot be loaded", plugin)
                    throw e
                }

            }

            val tlvCodec = router.findTlvCodec(serviceId)
            if( tlvCodec != null ) {
                val map = HashMap[String,String]()
                val keyMap = tlvCodec.msgKeyToTypeMapForRes.getOrElse(msgId,TlvCodec.EMPTY_STRINGMAP)

                for( (key,typeName) <- keyMap ) {
                  val classex = tlvCodec.codecAttributes.getOrElse("classex-"+typeName,null)
                  if( classex != null ) map.put(key,classex)
                  val tlvType = tlvCodec.typeNameToCodeMap.getOrElse(typeName,null)
                  if( tlvType.cls == TlvCodec.CLS_STRUCT ) {
                    for( n <- tlvType.structNames) {
                      val classex2 = tlvCodec.codecAttributes.getOrElse("classex-"+tlvType.name+"-"+n,null)
                      if( classex2 != null ) map.put(key+"-"+n,classex2)
                    }
                  }
                  if(  tlvType.cls == TlvCodec.CLS_STRUCTARRAY) {
                    for( n <- tlvType.structNames) {
                      val classex2 = tlvCodec.codecAttributes.getOrElse("classex-"+tlvType.itemType.name+"-"+n,null)
                      if( classex2 != null ) map.put(key+"-"+n,classex2)
                    }
                  }
                }
                classexMap.put(serviceId+"-"+msgId,map)
            }

            if( tlvCodec != null ) {

                val keyMap1 = tlvCodec.msgKeyToTypeMapForReq.getOrElse(msgId,TlvCodec.EMPTY_STRINGMAP)
                if( keyMap1.contains(sessionFieldName) )
                    msgAttrs.put(serviceId+"-"+msgId+"-sessionId-req","1")

                val keyMap2 = tlvCodec.msgKeyToTypeMapForRes.getOrElse(msgId,TlvCodec.EMPTY_STRINGMAP)
                if( keyMap2.contains(sessionFieldName) )
                    msgAttrs.put(serviceId+"-"+msgId+"-sessionId-res","1")

                if( keyMap1.contains(requestUriFieldName) )
                    msgAttrs.put(serviceId+"-"+msgId+"-"+requestUriFieldName,"1")
                if( keyMap1.contains(queryStringFieldName) )
                    msgAttrs.put(serviceId+"-"+msgId+"-"+queryStringFieldName,"1")
                if( keyMap1.contains(contentTypeFieldName) )
                    msgAttrs.put(serviceId+"-"+msgId+"-"+contentTypeFieldName,"1")
                if( keyMap1.contains(contentDataFieldName) )
                    msgAttrs.put(serviceId+"-"+msgId+"-"+contentDataFieldName,"1")
	
                val attributes = tlvCodec.msgAttributes.getOrElse(msgId,null)

                val cookiebuff1 = ArrayBuffer[Tuple3[String,String,String]]()
                for( (key,dummy) <- keyMap1 if key != sessionFieldName) {
                  val s = attributes.getOrElse("req-"+key+"-cookieName","")
                  if( s != "" )
                    cookiebuff1 += new Tuple3[String,String,String](key,s,null)
                }
                if( cookiebuff1.size > 0 ) cookieMap.put(serviceId+"-"+msgId+"-req",cookiebuff1)

                val cookiebuff2 = ArrayBuffer[Tuple3[String,String,String]]()
                for( (key,dummy) <- keyMap2 if key != sessionFieldName) {
                  val s = attributes.getOrElse("res-"+key+"-cookieName","")
                  val opt = attributes.getOrElse("res-"+key+"-cookieOption","")
                  if( s != "" )
                    cookiebuff2 += new Tuple3[String,String,String](key,s,opt)
                }
                if( cookiebuff2.size > 0 ) cookieMap.put(serviceId+"-"+msgId+"-res",cookiebuff2)


                val headerbuff1 = ArrayBuffer[Tuple2[String,String]]()
                for( (key,dummy) <- keyMap1 ) {
                  val s = attributes.getOrElse("req-"+key+"-headerName","")
                  if( s != "" )
                    headerbuff1 += new Tuple2[String,String](key,s)
                }
                if( headerbuff1.size > 0 ) headerMap.put(serviceId+"-"+msgId+"-req",headerbuff1)

                val headerbuff2 = ArrayBuffer[Tuple2[String,String]]()
                for( (key,dummy) <- keyMap2 ) {
                  val s = attributes.getOrElse("res-"+key+"-headerName","")
                  if( s != "" )
                    headerbuff2 += new Tuple2[String,String](key,s)
                }
                if( headerbuff2.size > 0 ) headerMap.put(serviceId+"-"+msgId+"-res",headerbuff2)

            }

        }

        nettyHttpServer = new NettyHttpServer(this, port, host, idleTimeout, maxContentLength)

        threadFactory = new NamedThreadFactory("httpserver")
        pool = new ThreadPoolExecutor(threadNum, threadNum, 0, TimeUnit.SECONDS, new ArrayBlockingQueue[Runnable](queueSize),threadFactory)
        pool.prestartAllCoreThreads()

        qte = new QuickTimerEngine(onTimeout,timerInterval)
    }

    def close() {

        if( timer != null) {
            timer.cancel()
            timer = null
        }

        if( pool != null ) {
            val t1 = System.currentTimeMillis
            pool.shutdown()
            pool.awaitTermination(5,TimeUnit.SECONDS)
            val t2 = System.currentTimeMillis
            if( t2 - t1 > 100 )
                log.warn("long time to close httpserver threadpool, ts={}",t2-t1)
        }

        nettyHttpServer.close()

        if( qte != null ) {
            qte.close()
            qte = null
        }

        log.info("httpserver stopped")
    }

    def afterInit() {

       if( osapDb ) {
         timer = new Timer("httpserverrefreshtimer")
         doRefresh()
         return
       }

       nettyHttpServer.start()
       listened.set(true)
       log.info("netty httpserver started port("+port+")")
    }

    def beforeClose() {
        nettyHttpServer.closeReadChannel()
    }

    def stats() : Array[Int] = {
        nettyHttpServer.stats
    }

    def dump() {
        val buff = new StringBuilder

        buff.append("pool.size=").append(pool.getPoolSize).append(",")
        buff.append("pool.getQueue.size=").append(pool.getQueue.size).append(",")
        buff.append("merchantMap.size=").append(merchantMap.get.size).append(",")
        buff.append("dataMap.size=").append(dataMap.size).append(",")
        buff.append("rpcMap.size=").append(rpcMap.size).append(",")

        log.info(buff.toString)

        nettyHttpServer.dump

        qte.dump()
    }

    def refresh() {

      if( timer == null ) return

      log.info("httpserver osap config data refreshing ...")

      if( timer != null ) {
         timer.cancel()
         timer = new Timer("httpserverrefreshtimer")
      }

      timer.schedule( new TimerTask() {
          def run() {
            doRefresh()
          }
        }, 500 )

    }

    def doRefresh() {

        val connId = "httpserverrefresh:0"

        val dummy = HashMapStringAny()
        osapRefreshData.set(new OsapRefreshData())
        for(msgId <- 1 to 4 ) {

            val requestId = uuid()

            val httpSosReq = new HttpSosRequest(requestId,connId,
                osapDbServiceId,msgId,
                HashMapStringAny(),""
                )

            val t = qte.newTimer(10000,requestId)
            val data = new HttpServerCacheData(httpSosReq, t)
            dataMap.put(requestId,data)

            val req = new Request (
                requestId,
                connId,
                sequence.getAndIncrement(),
                1,
                osapDbServiceId,
                msgId,
                dummy,
                dummy,
                this
            )

            router.send(req)
        }

        if( dataMap.size >= 1000 )
          log.warn("dataMap size is too large, dataMap.size="+dataMap.size)
        if( rpcMap.size >= 1000 )
          log.warn("rpcMap size is too large, rpcMap.size="+rpcMap.size)

    }

    def refreshTimeout(requestId:String,msgId:Int) {
        val d  = osapRefreshData.get()
        if( d == null ) return

        msgId match {
          case 1 => d.merchants = InvokeResult.timeout(requestId)
          case 2 => d.keys = InvokeResult.timeout(requestId)
          case 3 => d.ips = InvokeResult.timeout(requestId)
          case 4 => d.privileges = InvokeResult.timeout(requestId)
          case _ =>
        }

        if( d.allReplied ) refreshData(d)
    }

    def refreshResultReceived(requestId:String,msgId:Int,result:InvokeResult) {
        val d  = osapRefreshData.get()
        if( d == null ) return

        msgId match {
          case 1 => d.merchants = result
          case 2 => d.keys = result
          case 3 => d.ips = result
          case 4 => d.privileges = result
          case _ =>
        }

        if( d.allReplied ) refreshData(d)
    }

    def refreshData(d:OsapRefreshData) {

       osapRefreshData.set(null)

       val interval = if( listened.get() ) 600000 else 30000

       if( d.merchants.code != 0 ||
           d.keys.code != 0 ||
           d.ips.code != 0 ||
           d.privileges.code != 0 ) {

            timer.schedule( new TimerTask() {
              def run() {
                doRefresh()
              }
            }, interval )

         log.error("httpserver osap config data refresh failed, will retry after "+(interval/1000)+" seconds")
         return
       }

       val map = new HashMap[String,OsapMerchantCfg]()

       var merchantnames = d.merchants.ls("merchantname_array")
       val appids = d.merchants.li("appid_array")
       val areaids = d.merchants.li("areaid_array")

       if( merchantnames != null) {

           for( i <- 0 until merchantnames.size ) {
               val merchantname = merchantnames(i)
               val appid = appids(i)
               val areaid = areaids(i)
               map.put(merchantname,new OsapMerchantCfg(merchantname,appid,areaid))
           }

       }

       merchantnames = d.keys.ls("merchantname_array")
       val key1s = d.keys.ls("key1_array")
       val key1starttimes = d.keys.ls("key1starttime_array")
       val key1endtimes = d.keys.ls("key1endtime_array")
       val key2s = d.keys.ls("key2_array")
       val key2starttimes = d.keys.ls("key2starttime_array")
       val key2endtimes = d.keys.ls("key2endtime_array")

       if( merchantnames != null) {

           for( i <- 0 until merchantnames.size ) {
               val merchantname = merchantnames(i)
               val key1 = key1s(i)
               val key1starttime = key1starttimes(i)
               val key1endtime = key1endtimes(i)
               val key2 = key2s(i)
               val key2starttime = key2starttimes(i)
               val key2endtime = key2endtimes(i)

               val cfg = map.getOrElse(merchantname,null)
               if( cfg != null ) {
                 if( key1 != null && key1 != "")
                     cfg.md5Key += new OsapKeyData(key1,strToDate(key1starttime,0),strToDate(key1endtime,Long.MaxValue))
                 if( key2 != null && key2 != "")
                     cfg.md5Key += new OsapKeyData(key2,strToDate(key2starttime,0),strToDate(key2endtime,Long.MaxValue))
               }
           }

       }

       merchantnames = d.ips.ls("merchantname_array")
       val ipaddrs = d.ips.ls("ipaddr_array")

       if( merchantnames != null) {

           for( i <- 0 until merchantnames.size ) {
               val merchantname = merchantnames(i)
               val ipaddr = ipaddrs(i)

               val cfg = map.getOrElse(merchantname,null)
               if( cfg != null ) {
                 if( ipaddr != null && ipaddr != "")
                     cfg.ipSet.add(ipaddr)
               }
           }
       }

       merchantnames = d.privileges.ls("merchantname_array")
       val serviceids = d.privileges.li("serviceid_array")
       val msgids = d.privileges.li("msgid_array")

       if( merchantnames != null) {

           for( i <- 0 until merchantnames.size ) {
               val merchantname = merchantnames(i)
               val serviceid = serviceids(i)
               val msgid = msgids(i)

               val cfg = map.getOrElse(merchantname,null)
               if( cfg != null ) {
                   cfg.privilegeSet.add(serviceid+"-"+msgid)
               }
           }
       }

       merchantMap.set( map )

       log.info("httpserver osap config data refreshed")

       if( !listened.get() ) {
           nettyHttpServer.start()
           listened.set(true)
           log.info("httpserver started port("+port+")")
       }

        timer.schedule( new TimerTask() {
          def run() {
            doRefresh()
          }
        }, 600000 )

    }

    def receive(req:HttpRequest,connId:String)  {
        receive(new HttpServerRequest(req,connId))
    }

    def onTimeout(data:Any):Unit = {
        val requestId = data.asInstanceOf[String]
        receive(new HttpServerRequestTimeout(requestId))
    }

    override def receive(v:Any)  {

        try{
            pool.execute( new Runnable() {
                def run() {
                    try {
                        onReceive(v)
                    } catch {
                        case e:Exception =>
                            log.error("httpserver receive exception v={}",v,e)
                    }
                }
            })
        } catch {
            case e: RejectedExecutionException =>
                if( v.isInstanceOf[HttpServerRequest]) {
                    val r = v.asInstanceOf[HttpServerRequest]
                    val requestId = uuid()
                    replyError(r.httpReq,r.connId,requestId,ResultCodes.SERVICE_FULL)
                }
                log.error("httpserver queue is full")
        }

    }

    def onReceive(v:Any): Unit = {

        v match {

            case req: HttpServerRequest =>

                processRequest(req.httpReq,req.connId)

            case d: HttpServerRequestTimeout =>

                val data = dataMap.remove(d.requestId)
                if( data == null ) {
                    return
                }
                if( data.req.serviceId == osapDbServiceId) {
                  refreshTimeout(data.req.requestId,data.req.msgId)
                  return
                }

                val tpl = reply(data.req,ResultCodes.SERVICE_TIMEOUT,HashMapStringAny())
                updateRpcCall(tpl)

            case res: InvokeResult =>

                val requestId = res.requestId
                val data = dataMap.remove(requestId)
                if( data == null ) {
                    return
                }
                data.timer.cancel()

                if( data.req.serviceId == osapDbServiceId) {
                  refreshResultReceived(data.req.requestId,data.req.msgId,res)
                  return
                }

                val tpl = reply(data.req,res.code,res.res)
                updateRpcCall(tpl)

            case _ =>

                log.error("unknown msg received")
        }
    }

    def processRequest(httpReq:HttpRequest,connId:String)  {

        val method = httpReq.getMethod()
        if( method != HttpMethod.POST && method != HttpMethod.GET && method != HttpMethod.HEAD ) {
            val requestId = uuid()
            replyError(httpReq,connId,requestId,-10250013)
            return
        }


        val xhead = parseXhead(httpReq,connId)
        val requestId = uuid()

        var uri = httpReq.getUri()
        if( uri == jsonRpcUrl || uri.startsWith(jsonRpcUrl+"?") ) {
          processRequestJsonRpc(httpReq,connId,xhead,requestId)
          return
        }

        processRequest(httpReq,connId,xhead,requestId,uri) // not jsonrpc
    }

    def processRequestJsonRpc(httpReq:HttpRequest,connId:String,xhead:HashMapStringAny,requestId:String)  {

        val rpcRequestId = "rpc"+requestId

        val method = httpReq.getMethod()

        var jsonString = ""
        if( method == HttpMethod.POST ) {
            val content = httpReq.getContent()
            jsonString = content.toString(Charset.forName("UTF-8"))
        }
        if( method == HttpMethod.GET || method == HttpMethod.HEAD ) {
            val uri = httpReq.getUri()
            if( uri.indexOf("?") >= 0) {
                val s = uri.substring(uri.indexOf("?")+1)
                val map = HashMapStringAny()
                parseFormContent("UTF-8",s,map)
                jsonString = map.s("data","").toString
            }
        }

        if( jsonString == "" ) {
            replyError(httpReq,connId,requestId,ResultCodes.TLV_DECODE_ERROR)
            return
        }

        val reqs = JsonCodec.parseArray(jsonString)
        if( reqs == null || reqs.size == 0 ) {
            replyError(httpReq,connId,requestId,ResultCodes.TLV_DECODE_ERROR)
            return
        }

        val idSet = HashSet[String]()
        val rpcBuff = new ArrayBuffer[RpcInfo]()
        for( req <- reqs ) {

          if( !req.isInstanceOf[HashMapStringAny] ) {
            replyError(httpReq,connId,requestId,ResultCodes.TLV_DECODE_ERROR)
            return
          }

          val map = req.asInstanceOf[HashMapStringAny]
          val version = map.s("jsonrpc","")
          if( version != "2.0" ) {
            replyError(httpReq,connId,requestId,ResultCodes.TLV_DECODE_ERROR)
            return
          }

          val id = map.s("id","")
          if( id == "" || idSet.contains(id) ) {
            replyError(httpReq,connId,requestId,ResultCodes.TLV_DECODE_ERROR)
            return
          }

          idSet.add(id)

          val uri = map.s("method","")
          if( uri == null || uri == "" ) {
            replyError(httpReq,connId,requestId,ResultCodes.TLV_DECODE_ERROR)
            return
          }

          val params = map.getOrElse("params","")
          var bodyParams : HashMapStringAny = null

          if( params.isInstanceOf[String] ) {
            bodyParams = HashMapStringAny()
            parseFormContent("UTF-8",params.asInstanceOf[String],bodyParams)
          }
          if( params.isInstanceOf[HashMapStringAny] )
            bodyParams = params.asInstanceOf[HashMapStringAny]

          if( bodyParams == null ) {
            replyError(httpReq,connId,requestId,ResultCodes.TLV_DECODE_ERROR)
            return
          }

          rpcBuff += new RpcInfo(rpcRequestId+"-"+id,id,uri,bodyParams)
        }

        val rpcCall = new RpcCall(rpcBuff)
        rpcMap.put(rpcRequestId,rpcCall)
        for( rpc <- rpcBuff ) {
            val tpl = processRequest(httpReq,connId,xhead,rpc.requestId,rpc.uri,rpc.bodyParams)
            updateRpcCall(tpl)
        }
    }

    def processRequest(httpReq:HttpRequest,connId:String,xhead:HashMapStringAny,requestId:String,uri:String,bodyParams:HashMapStringAny = null):
        Tuple3[HttpSosRequestResponseInfo,HashMap[String,Cookie],HashMap[String,String]] = {

        val isRpc = requestId.startsWith("rpc")

        val (serviceId,msgId,mappingUri,patterns) = mappingUrl(uri)
        if( serviceId == 0 ) { // wrong

            if( !isRpc ) { // rpc 请求中不可以访问静态文件

                val file = mappingStaticUrl(uri)
                if( file != "" ) {
                    writeStaticFile(httpReq,connId,xhead,requestId,uri,new File(file))
                    return null
                }

                if( webappStaticDirExisted ) {
                    write404(httpReq,connId,xhead,requestId,uri)
                    return null
                }

            }

            val params = if( isRpc ) uri + "?" + bodyToParams(bodyParams) else uri
            val tpl = replyError(httpReq,connId,requestId,ResultCodes.SERVICE_NOT_FOUND,params)
            return tpl
        }

        val pluginObj = msgPlugins.getOrElse(serviceId+"-"+msgId,null)
        val clientIpPort = xhead.getOrElse(AvenueCodec.KEY_GS_INFO_FIRST,"").toString
        val clientIp = clientIpPort.substring(0,clientIpPort.indexOf(":"))
        val remoteIpPort = parseRemoteIp(connId)
        val serverIpPort = localIp + ":" + port
        var host = httpReq.getHeader("Host")
        if( host == null || host == "" ) host = "unknown_host"

        val (body,sessionIdChanged) = parseBody(isRpc,requestId,serviceId,msgId,httpReq,bodyParams,host,clientIp)

        if( patterns != null && patterns.size > 0 ) { // parse restful parameters
            val p = uri.indexOf("?")
            var path = if( p < 0 ) uri else uri.substring(0,p)
            if( path.startsWith("/") ) {
                path = path.substring(1)
            }
            val ss = path.split("/")
            for( (i,name) <- patterns if i >= 0 && i < ss.length  ) {
                body.put(name,ss(i))
            }
        }

        val whiteIps = whiteIpSet.getOrElse(serviceId+"-"+msgId,null)
        if( whiteIps != null && whiteIps.size > 0 ) {
            if( !whiteIps.contains(clientIp) ) {
                log.error("verify failed, not in white ips, uri="+uri)
                val params = if( isRpc ) uri + "?" + bodyToParams(bodyParams) else uri
                val tpl = replyError(httpReq,connId,requestId,-10250016,params,serviceId,msgId)
                return tpl
            }
        }

        val verify = isTrue ( msgAttrs.getOrElse(serviceId+"-"+msgId+"-verify",defaultVerify) )
        if( verify ) {

            if( pluginObj != null && pluginObj.isInstanceOf[HttpServerVerifyPlugin]) {
                val verifyOk = pluginObj.asInstanceOf[HttpServerVerifyPlugin].verify(serviceId,msgId,xhead,body,httpReq)
                if( !verifyOk ) {
                    val params = if( isRpc ) uri + "?" + bodyToParams(bodyParams) else uri
                    val tpl = replyError(httpReq,connId,requestId,-10250016,params,serviceId,msgId)
                    return tpl
                }
            } else {
                val (ok,merchantName) = getMerchantInfo(body)
                if( !ok ) {
                    log.error("verify failed, merchant name parameter not found, uri="+uri)
                    val params = if( isRpc ) uri + "?" + bodyToParams(bodyParams) else uri
                    val tpl = replyError(httpReq,connId,requestId,-10250016,params,serviceId,msgId)
                    return tpl
                }
                val cfg = merchantMap.get().getOrElse(merchantName,null)
                if( cfg == null ) {
                    log.error("verify failed, merchant cfg not found, uri="+uri)
                    val params = if( isRpc ) uri + "?" + bodyToParams(bodyParams) else uri
                    val tpl = replyError(httpReq,connId,requestId,-10250016,params,serviceId,msgId)
                    return tpl
                }

                val verifyOk = standardVerify(serviceId,msgId,xhead,body,cfg,uri)
                if( !verifyOk ) {
                    val params = if( isRpc ) uri + "?" + bodyToParams(bodyParams) else uri
                    val tpl = replyError(httpReq,connId,requestId,-10250016,params,serviceId,msgId)
                    return tpl
                }

            }
        }

        if( pluginObj != null && pluginObj.isInstanceOf[HttpServerRequestPostParsePlugin])
            pluginObj.asInstanceOf[HttpServerRequestPostParsePlugin].afterParse(serviceId,msgId,xhead,body)

        val caseInsensitive = isTrue( msgAttrs.getOrElse(serviceId+"-"+msgId+"-caseInsensitive","0") )
        val bodyIncase = if( caseInsensitive ) convertBodyCaseInsensitive(serviceId,msgId,body) else body

        val encodeRequestFlag = isTrue ( msgAttrs.getOrElse(serviceId+"-"+msgId+"-encodeRequest","1") )
//println("bodyIncase="+bodyIncase.mkString(","))
        val (bodyReal,ec) = if( encodeRequestFlag ) router.encodeRequest(serviceId,msgId,bodyIncase) else (bodyIncase,0)
//println("bodyReal="+bodyReal.mkString(","))

        if( ec != 0 ) {
            val params = if( isRpc ) uri + "?" + bodyToParams(bodyParams) else uri
            val tpl = replyError(httpReq,connId,requestId,ec,params,serviceId,msgId)
            return tpl
        }

        val req = new Request (
            requestId,
            connId,
            sequence.getAndIncrement(),
            1, // utf-8
            serviceId,
            msgId,
            xhead,
            bodyReal,
            this
        )

        val t = qte.newTimer(timeout,requestId)

        var params = "/" + mappingUri + "?" + bodyToParams(body)

        /*if( isRpc ) {
            params = mappingUri + "?" + bodyToParams(body)
        } else {
            val method = httpReq.getMethod()
            if( method != HttpMethod.GET && method != HttpMethod.HEAD ) {
                val p = mappingUri.indexOf("?")
                if( p >= 0) {
                    params = mappingUri.substring(0,p) + "?" + bodyToParams(body)
                } else {
                    params = mappingUri + "?" + bodyToParams(body)
                }
        }*/

        var userAgent =  httpReq.getHeader("User-Agent")
        if( userAgent == null || userAgent == "") userAgent = "-"
        if( !logUserAgent ) userAgent = "-"

        val jsonpCallback = body.s(jsonpName,"")

        val httpxhead = HashMapStringAny(
            "clientIpPort"->clientIpPort,
            "remoteIpPort"->remoteIpPort,
            "serverIpPort"->serverIpPort,
            "userAgent"->userAgent,
            "host"->host,
            "method"->httpReq.getMethod().toString,
            "keepAlive"->HttpHeaders.isKeepAlive(httpReq).toString,
            "sessionId"->body.s(sessionFieldName),
            "sessionIdChanged"->sessionIdChanged.toString,
            "sigstat"->(if(verify) "1" else "0"),
            "httpCode"->"200",
            jsonpName->jsonpCallback
            )

        val httpSosReq = new HttpSosRequest(requestId,connId,
            serviceId,msgId,
            httpxhead,params
            )

        val data = new HttpServerCacheData(httpSosReq, t)
        dataMap.put(requestId,data)

        val ret = router.send(req)
        if( ret == null) return null

        dataMap.remove(requestId)
        t.cancel()
        val tpl = reply(httpSosReq,ret.code,ret.res)
        tpl
    }

    def replyError(httpReq:HttpRequest,connId:String,requestId:String,errorCode:Int,uri:String = "",serviceId:Int = 0, msgId:Int = 0 ):Tuple3[HttpSosRequestResponseInfo,HashMap[String,Cookie],HashMap[String,String]] = {

        val isRpc = requestId.startsWith("rpc")

        var content = errorFormat.format(errorCode,convertErrorMessage(errorCode))

        val clientIpPort = parseClientIp(httpReq,connId)
        val remoteIpPort = parseRemoteIp(connId)
        val serverIpPort = localIp + ":" + port
        var host = httpReq.getHeader("Host")
        if( host == null || host == "") host = "unknown_host"
        var userAgent =  httpReq.getHeader("User-Agent")
        if( userAgent == null || userAgent == "") userAgent = "-"

        var params = uri
        if( uri == "" ) params = httpReq.getUri()
        var jsonpCallback = ""
        if( params.indexOf("?") >= 0) {
            val s = params.substring(params.indexOf("?")+1)
            jsonpCallback = parseFormField("UTF-8",s,jsonpName)
        }

        var contentType = MIMETYPE_JSON
        if( jsonpCallback != "" && contentType == MIMETYPE_JSON ) {
            contentType = MIMETYPE_JAVASCRIPT
            content = """%s(%s);""".format(jsonpCallback,content)
        }

        val httpxhead = HashMapStringAny(
            "clientIpPort"->clientIpPort,
            "remoteIpPort"->remoteIpPort,
            "serverIpPort"->serverIpPort,
            "userAgent"->userAgent,
            "host"->host,
            "method"->httpReq.getMethod().toString,
            "keepAlive"->HttpHeaders.isKeepAlive(httpReq).toString,
            "charset"->"UTF-8",
            "contentType"->contentType,
            "httpCode"->"200"
            )

        val req = new HttpSosRequest(requestId,connId,
            serviceId,msgId,
            httpxhead,params
            )

        val res = new HttpSosResponse(requestId,errorCode,content)
        val reqResInfo = new HttpSosRequestResponseInfo(req,res)

        if( !isRpc ) {
            write(connId,content,httpxhead,null,null)
            asynclog(reqResInfo)
            null
        } else {
            (reqResInfo,null,null)
        }

    }

    def reply(req:HttpSosRequest,errorCode:Int,a_body:HashMapStringAny):Tuple3[HttpSosRequestResponseInfo,HashMap[String,Cookie],HashMap[String,String]] = {

        val isRpc = req.requestId.startsWith("rpc")

        var body = a_body
        val serviceId = req.serviceId
        val msgId = req.msgId
        val connId = req.connId
        val keepAlive = req.xhead.s("keepAlive","true")
        val params = req.params
        val charset = msgAttrs.getOrElse(serviceId+"-"+msgId+"-charset","UTF-8")
        var contentType = msgAttrs.getOrElse(serviceId+"-"+msgId+"-responseContentType",MIMETYPE_JSON)
        val pluginParam = msgAttrs.getOrElse(serviceId+"-"+msgId+"-pluginParam",null)
        val bodyOnly = msgAttrs.getOrElse(serviceId+"-"+msgId+"-bodyOnly","0")
        val jsonpCallback = req.xhead.s(jsonpName,"")

        var errorMessage = convertErrorMessage(errorCode)
        if( errorCode != 0 ) {
            val s = fetchMessage(body)
            if( s != null && s != "" ) errorMessage = s
        }

        var content = ""
        val pluginObj = msgPlugins.getOrElse(serviceId+"-"+msgId,null)

        if( pluginObj != null && pluginObj.isInstanceOf[HttpServerPreOutputPlugin]) {
            body = pluginObj.asInstanceOf[HttpServerPreOutputPlugin].adjustBody(serviceId,msgId,errorCode,body)
        }

        val cookies = new HashMap[String,Cookie]()

        if( sessionMode == 1 ) {
            val sessionIdSupport = msgAttrs.getOrElse(serviceId+"-"+msgId+"-sessionId-req","0")
            if( sessionIdSupport == "1" && req.xhead.s("sessionIdChanged","true") == "true" ) {
              val c = new DefaultCookie(sessionCookieName,req.xhead.s("sessionId",""))
              c.setHttpOnly(sessionHttpOnly)
              cookies.put(sessionCookieName,c)
            }
        }
        if( sessionMode == 2 ) {
            val sessionIdSupport = msgAttrs.getOrElse(serviceId+"-"+msgId+"-sessionId-res","0")
            if( sessionIdSupport == "1" ) {
              val sessionId = body.s(sessionFieldName,"")
              if( sessionId != "") {
                  val c = new DefaultCookie(sessionCookieName,sessionId)
                  c.setHttpOnly(sessionHttpOnly)
                  cookies.put(sessionCookieName,c)
              }
              body.remove(sessionFieldName)
            }
        }

        val cookieBuff = cookieMap.getOrElse(serviceId+"-"+msgId+"-res",null)
        if( cookieBuff != null && cookieBuff.size > 0 ) {
          for( (fieldName,cookieName,opt) <- cookieBuff ) {
            val c = new DefaultCookie(cookieName,body.s(fieldName,""))
            if( opt != null && opt != "")
                updateCookieOption(c,opt)
            cookies.put(c.getName,c)
            body.remove(fieldName)
          }
        }

        val headers = new HashMap[String,String]()

        val headerBuff = headerMap.getOrElse(serviceId+"-"+msgId+"-res",null)
        if( headerBuff != null && headerBuff.size > 0 ) {
            for( (fieldName,headerName) <- headerBuff ) {
                val v = body.s(fieldName,"")
                if( v != null && v != "") headers.put(headerName,v)
                body.remove(fieldName)
            }
        }

        val redirectUrl302 = body.s(redirect302FieldName,"" )
        if( redirectUrl302 != "" ) {

            write302(connId,req.xhead,redirectUrl302)

            val reqResInfo = new HttpSosRequestResponseInfo(req,new HttpSosResponse(req.requestId,0,""))
            req.xhead.put("contentType",MIMETYPE_PLAIN)
            req.xhead.put("charset","UTF-8")
            req.xhead.put("httpCode","302")
            asynclog(reqResInfo)
            return null

        }

        if( pluginObj != null && pluginObj.isInstanceOf[HttpServerOutputPlugin]) {

            val domainName = getDomainName(req.xhead.s("host",""))
            if( !body.contains("domainName") ) body.put("domainName",domainName)
            val contextPath = getContextPath(params)
            if( contextPath != "" && !body.contains("contextPath") ) body.put("contextPath",contextPath)
            if( urlArgs != "" && !body.contains("urlArgs") ) body.put("urlArgs",urlArgs)
            content = pluginObj.asInstanceOf[HttpServerOutputPlugin].generateContent(serviceId,msgId,errorCode,errorMessage,body,pluginParam)
            val ext = body.remove("__file_ext__")
            if( !ext.isEmpty) {
                contentType = mimeTypeMap.getOrElse(ext.get.toString,contentType)
            }

        } else {

        		if( isTrue(bodyOnly) ) {

	                content = JsonCodec.mkString( jsonTypeProcess(req.serviceId,req.msgId,body) )

        		} else {

    	            val map = HashMapStringAny()
    	            map.put("return_code",errorCode)
    	            map.put("return_message",errorMessage)
    	            map.put("data",jsonTypeProcess(req.serviceId,req.msgId,body))
    	            content = JsonCodec.mkString(map)
	          }
        }

        if( jsonpCallback != "" && contentType == MIMETYPE_JSON ) {
            contentType = MIMETYPE_JAVASCRIPT
            content = """%s(%s);""".format(jsonpCallback,content)
        }
        val reqResInfo = new HttpSosRequestResponseInfo(req,new HttpSosResponse(req.requestId,errorCode,content))

        req.xhead.put("contentType",contentType)
        req.xhead.put("charset",charset)

        if( !isRpc ) {
            write(connId, content, req.xhead, cookies, headers)
            asynclog(reqResInfo)
            null
        } else {
            (reqResInfo, cookies, headers)
        }
    }

    def replyRpcCall(rpcRequestId:String,rpcCall:RpcCall) {

      rpcMap.remove(rpcRequestId)

      val firstReq = rpcCall.buff(0)
      var connId = firstReq.reqRes.req.connId

      var cookies = HashMap[String,Cookie]()
      var headers = HashMap[String,String]()

      val s = new StringBuilder()
      s.append("[")
      var first = true
      for( rpcInfo <- rpcCall.buff ) {

        if( !first ) s.append(",") else first = false
        val json = rpcFormat.format(rpcInfo.id,rpcInfo.reqRes.res.content)
        s.append(json)

        if( rpcInfo.cookies != null && rpcInfo.cookies.size > 0 ) cookies ++= rpcInfo.cookies
        if( rpcInfo.headers != null && rpcInfo.headers.size > 0 ) headers ++= rpcInfo.headers
      }
      s.append("]")

      val xhead = firstReq.reqRes.req.xhead
      xhead.put("contentType",MIMETYPE_JSON)
      xhead.put("charset","UTF-8")
      write(connId, s.toString, xhead , cookies, headers)

      for( rpcInfo <- rpcCall.buff ) {
          asynclog(rpcInfo.reqRes)
      }
    }

    def write302(connId:String,xhead:HashMapStringAny,url:String) {
        val keepAlive = xhead.s("keepAlive","true") == "true"
        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND)
        setDateHeader(response)
        if( !keepAlive ) response.setHeader(HttpHeaders.Names.CONNECTION, "close")
        response.setHeader(HttpHeaders.Names.LOCATION, url )
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, "0")
        nettyHttpServer.write(connId,response,keepAlive)
    }

    def write(connId:String, content:String, xhead:HashMapStringAny, cookies: HashMap[String,Cookie], headers: HashMap[String,String]){

        val method = xhead.s("method","POST")
        val keepAlive = xhead.s("keepAlive","true") == "true"
        val contentType = xhead.s("contentType","")
        val charset = xhead.s("charset","UTF-8")

        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

        response.setHeader(HttpHeaders.Names.SERVER, "jvmhttpserver/1.1.0")
        setDateHeader(response,true)

        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType)
        val buff = ChannelBuffers.wrappedBuffer(content.getBytes(charset))
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(buff.readableBytes()))

        if( cookies != null && cookies.size > 0 ) {
            val encoder = new CookieEncoder(true)
            for( (dummy,c) <- cookies ) {
                 encoder.addCookie(c)
            }
            response.setHeader(HttpHeaders.Names.SET_COOKIE, encoder.encode())
        }

        if( headers != null ) {
            for( (key,value) <- headers ) {
                response.setHeader(key,value)
            }
        }

        if( !keepAlive ) response.setHeader(HttpHeaders.Names.CONNECTION, "close")

        if ( method == "HEAD" ) {
            nettyHttpServer.write(connId,response,keepAlive)
            return
        }

        //if( log.isDebugEnabled)
        //    log.debug("http reply: " + content)

        response.setContent(buff)
        nettyHttpServer.write(connId,response,keepAlive)
    }

    def write404(httpReq:HttpRequest,connId:String,xhead:HashMapStringAny,requestId:String,uri:String) {
        val keepAlive =  HttpHeaders.isKeepAlive(httpReq)
        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
        setDateHeader(response)
        if( !keepAlive ) response.setHeader(HttpHeaders.Names.CONNECTION, "close")
        val buff = ChannelBuffers.wrappedBuffer("FILE_NOT_FOUND".getBytes())
        response.setContent(buff)
        val contentType = "text/plain"
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType )
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(buff.readableBytes()))
        nettyHttpServer.write(connId,response,keepAlive)
        val reqResInfo = logStaticFile(requestId,httpReq,connId,HttpResponseStatus.NOT_FOUND,contentType,buff.readableBytes())
        router.asyncLogActor.receive(reqResInfo)
    }

    def write304(httpReq:HttpRequest,connId:String,xhead:HashMapStringAny,requestId:String,uri:String,f:File) : Boolean = {

        val keepAlive =  HttpHeaders.isKeepAlive(httpReq)

        val ifModifiedSince = httpReq.getHeader(HttpHeaders.Names.IF_MODIFIED_SINCE)

        if (ifModifiedSince != null && ifModifiedSince != "" ){
            val ifModifiedSinceDate = df_tl.get.parse(ifModifiedSince)
            val ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime()/1000
            val fileLastModifiedSeconds = f.lastModified()/1000
            if(ifModifiedSinceDateSeconds == fileLastModifiedSeconds){
                val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED)
                setDateHeader(response)
                if( !keepAlive ) response.setHeader(HttpHeaders.Names.CONNECTION, "close")
                nettyHttpServer.write(connId,response,keepAlive)
                val ext = getExt(f.getName)
                val contentType = mimeTypeMap.getOrElse(ext,MIMETYPE_DEFAULT)
                val reqResInfo = logStaticFile(requestId,httpReq,connId,HttpResponseStatus.NOT_MODIFIED,contentType,f.length())
                router.asyncLogActor.receive(reqResInfo)
                return true
            }
        }

        false
    }

    def writeStaticFile(httpReq:HttpRequest,connId:String,xhead:HashMapStringAny,requestId:String,uri:String,f:File) {

        val keepAlive =  HttpHeaders.isKeepAlive(httpReq)

        if( write304(httpReq,connId,xhead,requestId,uri,f) ) return

        val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

        response.setHeader(HttpHeaders.Names.SERVER, "jvmhttpserver/1.1.0")
        setDateHeaderAndCache(response,f)

        if( !keepAlive ) response.setHeader(HttpHeaders.Names.CONNECTION, "close")
        val ext = getExt(f.getName)

        val contentType = mimeTypeMap.getOrElse(ext,MIMETYPE_DEFAULT)
        response.setHeader(HttpHeaders.Names.CONTENT_TYPE, contentType )

        val fileLength = f.length()
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(fileLength));
/*

If-Modified-Since   如果请求的部分在指定时间之后被修改则请求成功，未被修改则返回304代码 If-Modified-Since: Sat, 29 Oct 2010 19:43:31 GMT
If-Match    只有请求内容与实体相匹配才有效  If-Match: “737060cd8c284d8af7ad3082f209582d”
If-None-Match   如果内容未改变返回304代码，参数为服务器先前发送的Etag，与服务器回应的Etag比较判断是否改变   If-None-Match: “737060cd8c284d8af7ad3082f209582d”
If-Range    如果实体未改变，服务器发送客户端丢失的部分，否则发送整个实体。参数也为Etag  If-Range: “737060cd8c284d8af7ad3082f209582d”
If-Unmodified-Since 只在实体在指定时间之后未被修改才请求成功    If-Unmodified-Since: Sat, 29 Oct 2010 19:43:31 GMT

Date: Wed, 09 Dec 2015 01:47:15 GMT
Last-Modified: Fri, 27 Apr 2012 09:05:32 GMT
Expires: Wed, 09 Dec 2015 01:49:15 GMT
Cache-Control: max-age=120

Date	原始服务器消息发出的时间
Last-Modified ：指出原始服务器认为该变量最后修改的日期和时间，确实意思取决于原是服务器的实现和资源的属性。对文件，可能只是文件系统内最后修改时间
Expires ：指出响应被认为过期的日期/时间
Cache-Control   告诉所有的缓存机制是否可以缓存及哪种类型    Cache-Control: no-cache

Accept-Ranges: bytes
AGE: 38
ETAGS: "xxx"

*/

        val reqResInfo = logStaticFile(requestId,httpReq,connId,HttpResponseStatus.OK,contentType,fileLength)

        if ( httpReq.getMethod() == HttpMethod.HEAD ) {
            nettyHttpServer.write(connId,response,keepAlive)
            router.asyncLogActor.receive(reqResInfo)
            return
        }

        if( cacheEnabled && fileLength <= cacheFileSize && cacheFilesSet.contains(ext)) {
            var data = cacheMap.get(f.getPath)
            if( data == null ) {
                data = FileUtils.readFileToByteArray(f)
                cacheMap.put(f.getPath,data)
            }

            val buff = ChannelBuffers.wrappedBuffer(data)
            response.setContent(buff)
            nettyHttpServer.write(connId,response,keepAlive,reqResInfo)
            return
        }

        nettyHttpServer.writeFile(connId,response,keepAlive,f,fileLength,reqResInfo)
    }

    def parseBody(isRpc:Boolean,requestId:String,serviceId:Int,msgId:Int,httpReq:HttpRequest,bodyParams:HashMapStringAny,host:String,clientIp:String):Tuple2[HashMapStringAny,Boolean] = {

        val contentType = msgAttrs.getOrElse(serviceId+"-"+msgId+"-requestContentType",MIMETYPE_FORM)
        val charset = msgAttrs.getOrElse(serviceId+"-"+msgId+"-charset","UTF-8")

        val map = HashMapStringAny()
        var sessionIdChanged = false

        val headerBuff = headerMap.getOrElse(serviceId+"-"+msgId+"-req",null)
        if( headerBuff != null && headerBuff.size > 0 ) {
            for( (fieldName,headerName) <- headerBuff ) {
                val v = httpReq.getHeader(headerName)
                if( v != null) map.put(fieldName,v)
            }
        }

        val sessionIdSupport = msgAttrs.getOrElse(serviceId+"-"+msgId+"-sessionId-req","0")
        if( sessionIdSupport == "1" ) {
            var sessionId = ""
            val cookie = httpReq.getHeader("Cookie")
            if( cookie != null && cookie != "" ) {
                val cookies = new CookieDecoder().decode(cookie)
                if( cookies != null ) {
                    val it = cookies.iterator()
                    while( it.hasNext() ) {
                       val c = it.next()
                       if( c.getName == sessionCookieName ) {
                           sessionId = c.getValue
                       }
                    }
                }
            }
            if( sessionId != "" && sessionMode == 1 ) {
              if( !validateSessionId(sessionId,host,clientIp) ) {
                sessionId = ""
              }
            }
            if( sessionId == "" && sessionMode == 1) {
              sessionId = genSessionId(requestId,host,clientIp)
              sessionIdChanged = true
            }

            if( sessionId != "")
                map.put(sessionFieldName,sessionId)
        }

        val cookieBuff = cookieMap.getOrElse(serviceId+"-"+msgId+"-req",null)
        if( cookieBuff != null && cookieBuff.size > 0 ) {
            val cookie = httpReq.getHeader("Cookie")
            val m = HashMapStringString()
            if( cookie != null && cookie != "" ) {
                val cookies = new CookieDecoder().decode(cookie)
                if( cookies != null ) {
                    val it = cookies.iterator()
                    while( it.hasNext() ) {
                       val c = it.next()
                       m.put( c.getName , c.getValue)
                    }
                }
            }
            for( (fieldName,cookieName,dummy) <- cookieBuff ) {
                map.put(fieldName,m.getOrElse(cookieName,""))
            }
        }

        val requestUriSupport = msgAttrs.getOrElse(serviceId+"-"+msgId+"-"+requestUriFieldName,"0")
        if( requestUriSupport == "1" ) {		
			map.put(requestUriFieldName,httpReq.getUri())
		}
		
        val queryStringSupport = msgAttrs.getOrElse(serviceId+"-"+msgId+"-"+queryStringFieldName,"0")
        if( queryStringSupport == "1" ) {		
            val uri = httpReq.getUri()
            if( uri.indexOf("?") >= 0) {
                val s = uri.substring(uri.indexOf("?")+1)
				map.put(queryStringFieldName,s)
            } else {
				map.put(queryStringFieldName,"")
			}
		}
		
		val method = httpReq.getMethod()
        val contentTypeSupport = msgAttrs.getOrElse(serviceId+"-"+msgId+"-"+contentTypeFieldName,"0")
        if( contentTypeSupport == "1" && method == HttpMethod.POST ) {		
			map.put(contentTypeFieldName,contentType)
		}

        val contentDataSupport = msgAttrs.getOrElse(serviceId+"-"+msgId+"-"+contentDataFieldName,"0")
        if( contentDataSupport == "1" && method == HttpMethod.POST ) {		
			val content = httpReq.getContent()
			val contentStr = content.toString(Charset.forName(charset))
			map.put(contentDataFieldName,contentStr)
		}

        if( isRpc) {

          map ++= bodyParams

        } else {

            val uri = httpReq.getUri()
            if( uri.indexOf("?") >= 0) {
                val s = uri.substring(uri.indexOf("?")+1)
                parseFormContent(charset,s,map)
            }

            val pluginObj = msgPlugins.getOrElse(serviceId+"-"+msgId,null)
            if( method == HttpMethod.POST && contentType == MIMETYPE_MULTIPART ) {
			
				if( log.isDebugEnabled) {
					log.debug("http file upload, headers: "+toHeaders(httpReq))
				}
				// upload file 
				// upload file to temp dir and put filename to body
				val content = httpReq.getContent()
//println("content="+content.toString(Charset.forName(charset)))
				parseFileUploadContent(charset,content,map)
				
            } else if( method == HttpMethod.POST ) {
                val content = httpReq.getContent()
                val contentStr = content.toString(Charset.forName(charset))
                if( log.isDebugEnabled) {
                    log.debug("http post content: " + contentStr+", headers: "+toHeaders(httpReq))
                }
                if( contentType == MIMETYPE_FORM ) {
                    parseFormContent(charset,contentStr,map)
                } else {
                    if( pluginObj != null && pluginObj.isInstanceOf[HttpServerRequestParsePlugin])
                        pluginObj.asInstanceOf[HttpServerRequestParsePlugin].parseContent(serviceId,msgId,charset,contentType,contentStr,map)
                }
            }

        }

        (map,sessionIdChanged)
    }

    def asynclog(reqResInfo:HttpSosRequestResponseInfo) {

      val buff = logFilterMap.getOrElse(reqResInfo.req.serviceId+"-"+reqResInfo.req.msgId,null)
      if( buff != null && buff.size > 0 ) {
        for( r <- buff ) {
            reqResInfo.req.params = r.replaceAllIn(reqResInfo.req.params,"")
            reqResInfo.res.content = r.replaceAllIn(reqResInfo.res.content,"")
        }
      }

      router.asyncLogActor.receive(reqResInfo)
    }

    def genSessionId(requestId:String,host:String,clientIp:String):String = {
          val t =
              if( requestId.startsWith("rpc") ) {
                val p = requestId.indexOf("-")
                requestId.substring(3,p)
              }
              else {
                requestId
              }
          if( !sessionIpBind ) return t
          val data = t + "#" + clientIp
          val s = CryptHelper.encryptHex(CryptHelper.ALGORITHM__DES,sessionEncKey,data)
          s
    }
    def validateSessionId(sessionId:String,host:String,clientIp:String):Boolean = {
          if( !sessionIpBind ) return true
          try {
              val s = CryptHelper.decryptHex(CryptHelper.ALGORITHM__DES,sessionEncKey,sessionId)
              if( s == null ) return false
              val ss = s.split("#")
              if( ss.length != 2 ) return false
              if( ss(1) != clientIp ) return false
              true
          } catch {
              case _ :Throwable => false
          }
    }

    def updateRpcCall(tpl:Tuple3[HttpSosRequestResponseInfo,HashMap[String,Cookie],HashMap[String,String]]) {
      if( tpl == null ) return
      val requestId = tpl._1.req.requestId
      val p = requestId.indexOf("-")
      if( p < 0 ) return
      val rpcRequestId = requestId.substring(0,p)
      val rpcCall = rpcMap.get(rpcRequestId)
      if( rpcCall == null ) return
      rpcCall.updateResult(tpl)
      if( rpcCall.finished) replyRpcCall(rpcRequestId,rpcCall)
    }

    def getMerchantInfo(body:HashMapStringAny):Tuple2[Boolean,String] = {

        val merchant_name = body.s("merchant_name","")
        val signature_method = body.s("signature_method","")
        val signature = body.s("signature","")
        val timestamp = body.s("timestamp","")

        if( merchant_name == "") return new Tuple2(false,null)
        if( signature_method == "") return new Tuple2(false,null)
        if( signature == "") return new Tuple2(false,null)
        if( timestamp == "") return new Tuple2(false,null)

        (true,merchant_name)
    }

    def standardVerify(serviceId:Int,msgId:Int,xhead:HashMapStringAny,body:HashMapStringAny,cfg:OsapMerchantCfg,uri:String):Boolean = {

      val signMap = new java.util.TreeMap[String,String]()

      for( (key,value) <- body if key != "signature" ) { // donot ignore sessionId,cookie,header field, in that case standardVerify will not be called
        signMap.put(key,value.toString)
      }

      val signString = new StringBuilder()

      val entries = signMap.entrySet().iterator()
      while( entries.hasNext() ) {
        val entry = entries.next()
        signString.append( entry.getKey ).append("=").append( entry.getValue )
      }

      val reqSignature = body.s("signature")
      var ok = false
      val now = System.currentTimeMillis
      for(key <- cfg.md5Key if key.st <= now && key.et >= now ) {
          val s = signString.append(key.key)
          val signature = CryptHelper.md5( signString.toString )
          if( signature == reqSignature ) ok = true
      }

      if(!ok) {
        log.error("verify failed, md5 verify error, uri="+uri)
        return false
      }

      val clientIpPort = xhead.getOrElse(AvenueCodec.KEY_GS_INFO_FIRST,"").toString
      val clientIp = clientIpPort.substring(0,clientIpPort.indexOf(":"))

      if( !cfg.ipSet.contains(clientIp) ) {
        log.error("verify failed, ip verify error, uri="+uri)
        return false
      }

      if( !cfg.privilegeSet.contains(serviceId+"-"+msgId) ) {
        log.error("verify failed, privilege verify error, uri="+uri)
        return false
      }

      xhead.put(AvenueCodec.KEY_SOC_ID,cfg.merchantName)
      xhead.put(AvenueCodec.KEY_APP_ID,cfg.appId)
      xhead.put(AvenueCodec.KEY_AREA_ID,cfg.areaId)

      true
  }

    def bodyToParams(body:HashMapStringAny):String = {
        if( body == null ) return ""
        val buff = new StringBuilder
        for( (key,value) <- body ) {
            if( buff.length > 0 ) buff.append("&")
            buff.append(key+"="+URLEncoder.encode(value.toString,"UTF-8"))
        }
        buff.toString
    }

    def mappingUrl(uri:String):Tuple4[Int,Int,String,ArrayBufferPattern] = {

        var part = uri.toLowerCase
        val p = part.indexOf("?")
        if( p >= 0) {
            val params = part.substring(p+1)
            part = part.substring(0,p)
            val p1 = params.indexOf("method=")
            if( p1 >= 0) {
               var method = parseFormField("UTF-8",params,"method")
               if( method != "" ) {
                  part += "/" + method
               }
            }
        }

        if( part.startsWith("/") ) {
            part = part.substring(1)
        }

        mappingUrlInternal(part)
    }

    def mappingUrlInternal(uri:String):Tuple4[Int,Int,String,ArrayBufferPattern] = {

        var part = uri
        var hasSlash = false
        var tpl : Tuple4[Int,Int,String,ArrayBufferPattern] = null
        do {
            if( enableMock ) {
                tpl =  urlMapping.getOrElse(part+"/mock",null)
                if( tpl != null ) return tpl
            }
            tpl =  urlMapping.getOrElse(part,null)
            if( tpl != null ) return tpl

            if( hasPattern ) {
                if( enableMock ) {
                    tpl =  urlMapping.getOrElse(part+"/*/*/mock",null)
                    if( tpl != null ) return tpl
                }
                tpl =  urlMapping.getOrElse(part+"/*/*",null)
                if( tpl != null ) return tpl
                if( enableMock ) {
                    tpl =  urlMapping.getOrElse(part+"/*/mock",null)
                    if( tpl != null ) return tpl
                }
                tpl =  urlMapping.getOrElse(part+"/*",null)
                if( tpl != null ) return tpl
            }

            val t = part.lastIndexOf("/")
            hasSlash = t >= 0

            if( hasSlash ) {
                part = part.substring(0,t)
            }

        } while( hasSlash )

        if( hasPattern ) {
            if( enableMock ) {
                tpl =  urlMapping.getOrElse("*/*/mock",null)
                if( tpl != null ) return tpl
            }
            tpl =  urlMapping.getOrElse("*/*",null)
            if( tpl != null ) return tpl
            if( enableMock ) {
                tpl =  urlMapping.getOrElse("*/mock",null)
                if( tpl != null ) return tpl
            }
            tpl =  urlMapping.getOrElse("*",null)
            if( tpl != null ) return tpl
        }

        (0,0,"",null)
    }

    def mappingStaticUrl(uri:String):String = {

        if( !webappStaticDirExisted ) return ""
        if( uri.indexOf("..") >= 0 ) return ""

        var part = uri
        val p = uri.indexOf("?")
        if( p >= 0) {
            part = uri.substring(0,p)
        }
        if( part.startsWith("/") ) {
            part = part.substring(1)
        }

        if( skipMinFile ) {
            part = StringUtils.replace(part,".min.",".")
        }

        if( part == "" ) part = "index.html"
        if( part.endsWith("/") )  part += "index.html"

        val f = webappStaticDir + File.separator + part
        val file = new File(f)
        if( !file.exists() || file.isHidden() || file.isDirectory() ) return ""
        if( !file.getPath.startsWith(webappStaticDir) ) return ""
        f
    }

    def logStaticFile(requestId:String,httpReq:HttpRequest,connId:String,httpCode:HttpResponseStatus,contentType:String,contentLength:Long):HttpSosRequestResponseInfo = {

        val clientIpPort = parseClientIp(httpReq,connId)
        val remoteIpPort = parseRemoteIp(connId)
        val serverIpPort = localIp + ":" + port
        var host = httpReq.getHeader("Host")
        if( host == null || host == "") host = "unknown"
        val keepAlive =  HttpHeaders.isKeepAlive(httpReq).toString
        var userAgent =  httpReq.getHeader("User-Agent")
        if( userAgent == null || userAgent == "") userAgent = "-"

        var params = httpReq.getUri()

        val httpxhead = HashMapStringAny(
            "clientIpPort"->clientIpPort,
            "remoteIpPort"->remoteIpPort,
            "userAgent"->userAgent,
            "serverIpPort"->serverIpPort,
            "host"->host,
            "method"->httpReq.getMethod().toString,
            "keepAlive"->keepAlive,
            "contentType"->contentType,
            "contentLength"->contentLength,
            "staticFile"->"1",
            "httpCode"->httpCode.getCode.toString
            )

        val req = new HttpSosRequest(requestId,connId,
            0,0,
            httpxhead,params
            )

        val res = new HttpSosResponse(requestId,httpCode.getCode,"")
        val reqResInfo = new HttpSosRequestResponseInfo(req,res)
        reqResInfo
    }

    def setDateHeader(response:HttpResponse,addNoCache:Boolean=false) {
        val time = new GregorianCalendar();
        response.setHeader(HttpHeaders.Names.DATE, df_tl.get().format(time.getTime()));
        if( addNoCache )
            response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "no-cache");
    }

    def setDateHeaderAndCache(response:HttpResponse,f:File) {
        val time = new GregorianCalendar();
        response.setHeader(HttpHeaders.Names.DATE, df_tl.get().format(time.getTime()));
        time.add(Calendar.SECOND, httpCacheSeconds);
        response.setHeader(HttpHeaders.Names.EXPIRES, df_tl.get.format(time.getTime()));
        response.setHeader(HttpHeaders.Names.LAST_MODIFIED, df_tl.get.format(new Date(f.lastModified())));
        if( httpCacheSeconds == 0 )
            response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "no-cache");
        else
            response.setHeader(HttpHeaders.Names.CACHE_CONTROL, "max-age=" + httpCacheSeconds);
    }

    def getDomainName(s:String):String = {
        val p = s.indexOf(":")
        if( p >= 0 ) return s.substring(0,p)
        else s
    }
    def getContextPath(params:String ):String = {
        val p = params.indexOf("/")
        if( p != 0 ) return "/"
        val p2 = params.indexOf("/",p+1)
        if( p2 > 0 ) return params.substring(0,p2)
        val p3 = params.indexOf("?",p+1)
        if( p3 > 0 ) return params.substring(0,p3)
        else params
    }

    def getExt(f:String):String = {
        val p = f.lastIndexOf(".")
        if( p < 0 ) return ""
        val ext = f.substring(p+1).toLowerCase
        ext
    }

    def parseIp(s:String):String = {
        val p = s.lastIndexOf(":")
        if (p >= 0)
            s.substring(0,p)
        else
            s
    }

    def parseRemoteIp(connId:String):String = {
        var clientIp = parseIp(connId)
        clientIp
    }

    def parseClientIp(httpReq:HttpRequest,connId:String):String = {
        var clientIp = parseIp(connId)

        val xff = httpReq.getHeader("X-Forwarded-For")
        if( xff != null && xff != "" ) {
            val ss = xff.split(",")
            if( ss.length > 0 ) clientIp = ss(0).trim
        }

        if( clientIp.indexOf(":") < 0 ) clientIp += ":80"

        clientIp
    }

    def parseHttpType(httpReq:HttpRequest):String = {

        var ht = httpReq.getHeader("HTTP_X_Forwarded_Proto")
        if( ht == null || ht == "" ) {
            ht = httpReq.getHeader("X_HTTP_SCHEME")
        }
        if( ht == null ) return null
        val s = ht match {
            case "http" => "1"
            case "https" => "2"
            case _ => null
        }

        s
    }

    def parseXhead(httpReq:HttpRequest,connId:String):HashMapStringAny = {
        var clientIp = parseClientIp(httpReq,connId)
        val map = HashMapStringAny()
        map.put(AvenueCodec.KEY_GS_INFOS,ArrayBufferString(clientIp))
        map.put(AvenueCodec.KEY_GS_INFO_FIRST,clientIp)
        map.put(AvenueCodec.KEY_GS_INFO_LAST,clientIp)

        val httpType = parseHttpType(httpReq)
        if( httpType != null ) {
            map.put(AvenueCodec.KEY_HTTP_TYPE,httpType)      
        }

        map
    }

    def updateCookieOption(c:Cookie,opt:String) {
      val ss = opt.split(";")
      for( s <- ss ) {
        val ss2 = s.split("=")
        ss2(0) match {
          case "Path" => c.setPath(ss2(1))
          case "MaxAge" => c.setMaxAge(ss2(1).toInt)
          case "Domain" => c.setDomain(ss2(1))
          case "Ports" => c.setPorts(ss2(1).toInt) // support only one port
          case "HttpOnly" => c.setHttpOnly(isTrue(ss2(1)))
          case "Discard" => c.setDiscard(isTrue(ss2(1)))
          case "Secure" => c.setSecure(isTrue(ss2(1)))
          case "Version" => c.setVersion(ss2(1).toInt)
          case _ =>
        }
      }
    }

    def convertBodyCaseInsensitive(serviceId:Int,msgId:Int,body:HashMapStringAny):HashMapStringAny =  {
      val tlvCodec = router.findTlvCodec(serviceId)
      if( tlvCodec == null )  return null

      for( (key,value) <- body if value != null) {
            body.put(key.toLowerCase,value)
      }

      val map = new HashMapStringAny()
      val keyMap = tlvCodec.msgKeyToTypeMapForReq.getOrElse(msgId,TlvCodec.EMPTY_STRINGMAP)
      for( (key,value) <- keyMap ) {
        val v = body.s(key.toLowerCase,null)
        if( v != null )
            map.put(key,v)
      }

      map
    }

    def uuid(): String = {
       return java.util.UUID.randomUUID().toString().replaceAll("-", "")
    }

    def parseFormContent(charSet:String, contentStr:String, body: HashMapStringAny ) {

        val ss = contentStr.split("&")

        for( s <- ss ) {
            val tt = s.split("=")
            if( tt.size >= 2 ) {
                val key = tt(0)
                val value = URLDecoder.decode(tt(1),charSet)
                var s = body.s(key)
                if( s != null && s != "") s += ","+value
                else s = value
                body.put(key,s)
            }
        }

    }

    def parseFormField(charSet:String, s:String, field: String ) : String = {

        val contentStr = "&"+s
        val p1 = contentStr.indexOf("&"+field+"=")
        if( p1 < 0 ) return ""
        val p2 = contentStr.indexOf("&",p1+1)
        var value = ""
        if( p2 < 0 ) {
            value = contentStr.substring(p1+1+field.length+1)
        } else {
            value = contentStr.substring(p1+1+field.length+1,p2)
        }
        value = URLDecoder.decode(value,charSet)
        value
    }

    def fetchMessage(body:HashMapStringAny):String = {
        for( key <- returnMessageFieldNames ) {
            val s = body.s(key,"")
            if( s != "" ) return s
        }
        null
    }

    def convertErrorMessage(errorCode:Int):String = {
        errorCode match {
            case 0 => "success"
            case ResultCodes.SERVICE_NOT_FOUND => "service not found"
            case ResultCodes.SERVICE_TIMEOUT => "service timeout"
            case ResultCodes.SERVICE_BUSY => "queue is full"
            case ResultCodes.TLV_ENCODE_ERROR => "parameters encode/decode error"
            case ResultCodes.SERVICE_INTERNALERROR => "service internal error"
            case ResultCodes.SOC_NETWORKERROR => "network error"
            case -10250013 => "not supported url"
            case -10250016 => "server reject"
            case _ => "unknown error message " + errorCode
        }
    }

    def jsonTypeProcess(serviceId:Int,msgId:Int,body:HashMapStringAny):HashMapStringAny = {

      val fields = classexMap.getOrElse(serviceId+"-"+msgId,null)
      if( fields == null || fields.size == 0 ) return body

      val newBody = HashMapStringAny()
      for( (key,value) <- body ) {

       if( !fields.contains(key) ) {

         newBody.put(key,value)

       } else {

        value match {

          case s:String =>
                val v = convertValue(value,key,fields)
                newBody.put(key,v)

          case i:Int =>
                val v = convertValue(value,key,fields)
                newBody.put(key,v)

          case m:HashMapStringAny =>

              val newMap = convertMap(m,key,fields)
              newBody.put(key,newMap)

          case ls:ArrayBufferString =>

              val buff = ArrayBufferAny()
              for( value2 <- ls ) {
                    val v = convertValue(value2,key,fields)
                    buff += v
              }
              newBody.put(key,buff)

          case li:ArrayBufferInt =>
              val buff = ArrayBufferAny()
              for( value2 <- li ) {
                    val v = convertValue(value2,key,fields)
                    buff += v
              }
              newBody.put(key,buff)

          case lm:ArrayBufferMap =>
              val buff = ArrayBufferAny()
              for( value2 <- lm ) {
                    val v = convertMap(value2,key,fields)
                    buff += v
              }
              newBody.put(key,buff)

          case _ =>
            newBody.put(key,value)
        }

       }

      }

      newBody
    }

    def convertMap(m:HashMapStringAny,key:String,fields:HashMap[String,String]):Any = {
          val newMap = HashMapStringAny()
          for( (key2,value2) <- m ) {
                val v = convertValue(value2,key+"-"+key2,fields)
                newMap.put(key2,v)
          }
          newMap
    }

    def convertValue(value:Any,key:String,fields:HashMap[String,String]):Any = {

       if( value == null || value == "" || !fields.contains(key) ) {
           return value
       }

       val classex = fields.getOrElse(key,null)
       classex match {
         case "json" =>
           JsonCodec.parseObject(value.toString)
         case "double" =>
           value.toString.toDouble
         case "long" =>
           value.toString.toLong
         case "string" =>
           value.toString
         case _ =>
           value
         }

    }

    def strToDate(as:String,defaultValue:Long):Long = {

        var s = as
        if( s == null || s == "" ) return defaultValue

        if( s.length() == 10) {
            s += " 00:00:00";
        }
        if( s.length() == 13) {
            s += ":00:00";
        }
        if( s.length() == 16) {
            s += ":00";
        }

        var t = 0L

        try {
            t = TimeHelper.convertTimestamp(s).getTime
        } catch {
            case e:Exception =>
                return defaultValue
        }

        if( TimeHelper.convertTimestamp(new Date(t)) != s ) {
                return defaultValue
        }

        t
    }

    def toHeaders(httpReq:HttpRequest):String = {
        val headers = httpReq.getHeaders()
        val size = headers.size
        val buff = new StringBuilder()
        for(i <- 0 until size) {
            val entry = headers.get(i)
            if( i > 0 ) buff.append(",")
            buff.append(entry.getKey).append("=").append(entry.getValue)
        }
        buff.toString
    }

    def isTrue(s:String):Boolean = {
       s == "1"  || s == "t"  || s == "T" || s == "true"  || s == "TRUE" || s == "y"  || s == "Y" || s == "yes" || s == "YES"
    }

	def parseFileUploadContent(charset:String,buffer:ChannelBuffer,map:HashMapStringAny) {
		var delimeter = parseDelimeter(charset,buffer)
		if( delimeter == "" ) return
		val db = delimeter.getBytes()

		var over = false	
		val params = ArrayBufferMap()
		while(buffer.readable() && !over ) { 
			val m = parsePartAttrs(charset,buffer)
			if( m.contains("filename") ) {
				val (filename,finished) = readMultiPartFile(charset,buffer,db)
				if( filename != "")
					m.put("file",filename)
				over = finished	
			} else {
				val (v,finished) = readMultiPartValue(charset,buffer,db)
				m.put("value",v)
				over = finished	
			}
			params += m
		}
		val files = ArrayBufferMap()
		for( m <- params ) {
			if( m.contains("filename") ) {
				if( m.contains("file") ) {
					val filename = m.s("filename","")
					val p = filename.lastIndexOf(".")
					if( p > 0 ) m.put("ext",filename.substring(p).toLowerCase)
					else m.put("ext","")
					
					val file = m.s("file","")
					m.put("size",new File(file).length)
					
					files += m
				}
			} else {
				val name = m.s("name","")
				val value = m.s("value","")
				if( name != "") {
					map.put(name,value)
				}
			}
		}
		map.put("files",files)
	}
	
	def readMultiPartFile(charset:String,buffer:ChannelBuffer,db:Array[Byte]):Tuple2[String,Boolean] = {

		val webappUploadDirExisted = new File(webappUploadDir).exists()
		if( !webappUploadDirExisted ) {
			new File(webappUploadDir).mkdirs()
		}
		val filename = webappUploadDir + File.separator+uuid()+".tmp"
		
		var writed = 0
		val buf = new FileOutputStream(filename); 
		while(buffer.readable()) { 
			val b = buffer.readByte()
			if ( b == '\r' && buffer.readable() )	 {
				val b2 = buffer.readByte()
				if( b2 == '\n' && buffer.readableBytes >= db.size + 2 ) {
					buffer.markReaderIndex()
					val (matched,finished) = cmp(buffer,db)
					if( matched ) {
						buf.close()
						if( writed > 0 ) {
							return new Tuple2(filename,finished)
						} else {
							new File(filename).delete()	
							return new Tuple2("",finished)
						}
					} else {
						buf.write(b)
						buf.write(b2)
						writed += 2
						buffer.resetReaderIndex()
					} 
				} else {
					buf.write(b)
					buf.write(b2)
					writed += 2
				}
			} else {
				buf.write(b)
				writed += 1
			}
		} 
		
		buf.close()
		("",true)
	}
	
	def readMultiPartValue(charset:String,buffer:ChannelBuffer,db:Array[Byte]):Tuple2[String,Boolean] = {
		val buf = new ByteArrayOutputStream(); 
		while(buffer.readable()) { 
			val b = buffer.readByte()
			if ( b == '\r' && buffer.readable() )	 {
				val b2 = buffer.readByte()
				if( b2 == '\n' && buffer.readableBytes >= db.size + 2 ) {
					buffer.markReaderIndex()
					val (matched,finished) = cmp(buffer,db)
					if( matched ) {
						val v = buf.toString(charset)
						return new Tuple2(v,finished)
					} else {
						buf.write(b)
						buf.write(b2)
						buffer.resetReaderIndex()
					} 
				} else {
					buf.write(b)
					buf.write(b2)
				}
			} else {
				buf.write(b)
			}
		} 
		
		("",true)
	}

	def cmp(buffer:ChannelBuffer, db:Array[Byte]):Tuple2[Boolean,Boolean] = {
		var i = 0
		while( i < db.size) {
			val b = buffer.readByte()
			if( b != db(i) ) return new Tuple2(false,false)
			i += 1
		}
		val b1 = buffer.readByte()
		val b2 = buffer.readByte()
		
		val finished = ( b1 == '-' && b2 == '-' )
		(true,finished)
	}
	
	def parseDelimeter(charset:String,buffer:ChannelBuffer):String = {
		val buf = new ByteArrayOutputStream(); 
		while(buffer.readable()) { 
			val b = buffer.readByte()
			buf.write(b)
			if ( b == '\n')	 {
				val line = buf.toString(charset);  
				return line.trim
			}
		} 
		
		""
	}
	
	def parsePartAttrs(charset:String,buffer:ChannelBuffer):HashMapStringAny = {
		val map = HashMapStringAny()
		val str = parsePart(charset,buffer)
		if( str == "" ) return map
		val lines = str.split("\r\n")
		for(line <- lines) {
			val p = line.indexOf(":")
			if( p > 0 ) {
				val key = line.substring(0,p)
				key match {
					case "Content-Disposition" =>
						val s = line.substring(p+1).trim
						val ss = s.split(";")
						for( ts <- ss ) {
							val tss = ts.trim.split("=")
							tss(0) match {
								case "name" =>
									val v = tss(1).replace("\"","")
									map.put("name",v)
								case "filename" =>
									var v = tss(1).replace("\"","")
									val p1 = v.lastIndexOf("/")
									if( p1 >= 0 ) v = v.substring(p1+1)
									val p2 = v.lastIndexOf("\\")
									if( p2 >= 0 ) v = v.substring(p2+1)
									map.put("filename",v)
								case _ =>
							}
						}
					case "Content-Type" => 
						val contentType = line.substring(p+1).trim()
						map.put("contentType",contentType)
					case _ =>
				}
			}
		}
		map
	}	
	
	def parsePart(charset:String,buffer:ChannelBuffer):String = {
		val buf = new ByteArrayOutputStream(); 
		while(buffer.readable()) { 
			val b = buffer.readByte()
			buf.write(b)
			if ( b == '\n' ) {
				val line = buf.toString(charset)
				if( line.endsWith("\r\n\r\n") )	 {
					return line
				}
			} 
		} 
		
		""
	}

}

