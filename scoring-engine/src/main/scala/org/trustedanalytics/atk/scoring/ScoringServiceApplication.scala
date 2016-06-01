/**
 *  Copyright (c) 2015 Intel Corporation 
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.trustedanalytics.atk.scoring

import java.io.{ FileOutputStream, File, FileInputStream }

import akka.actor.{ ActorSystem, Props }
import akka.io.IO
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{ FileSystem, Path }
import org.trustedanalytics.atk.moduleloader.{ ClassLoaderAware, Component }
import org.trustedanalytics.hadoop.config.client.oauth.TapOauthToken
import spray.can.Http
import org.trustedanalytics.atk.model.publish.format.ModelPublishFormat
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration._
import org.trustedanalytics.atk.event.EventLogging
import com.typesafe.config.{ Config, ConfigFactory }
import scala.reflect.ClassTag
import org.trustedanalytics.atk.scoring.interfaces.{ ModelLoader, Model }
import org.trustedanalytics.hadoop.config.client.helper.Hdfs
import java.net.URI
import org.apache.commons.io.FileUtils

import org.apache.http.message.BasicNameValuePair
import org.apache.http.auth.{ AuthScope, UsernamePasswordCredentials }
import org.apache.http.impl.client.{ BasicCredentialsProvider, HttpClientBuilder }
import org.apache.http.client.methods.{ HttpPost, CloseableHttpResponse }
import org.apache.http.HttpHost
import org.apache.http.client.config.RequestConfig
import org.apache.http.client.entity.UrlEncodedFormEntity
import java.util.{ ArrayList => JArrayList }
import spray.json._

/**
 * Scoring Service Application - a REST application used by client layer to communicate with the Model.
 *
 * See the 'scoring_server.sh' to see how the launcher starts the application.
 */
class ScoringServiceApplication extends Component with EventLogging with ClassLoaderAware {

  val config = ConfigFactory.load(this.getClass.getClassLoader)

  EventLogging.raw = true
  info("Scoring server setting log adapter from configuration")

  EventLogging.raw = config.getBoolean("trustedanalytics.atk.scoring.logging.raw")
  info("Scoring server set log adapter from configuration")

  EventLogging.profiling = config.getBoolean("trustedanalytics.atk.scoring.logging.profile")
  info(s"Scoring server profiling: ${EventLogging.profiling}")

  /**
   * Main entry point to start the Scoring Service Application
   */
  override def start() = {
    val model = getModel
    val service = new ScoringService(model)
    withMyClassLoader {

      createActorSystemAndBindToHttp(service)
    }
  }

  /**
   * Stop this component
   */
  override def stop(): Unit = {
  }

  private def getModel: Model = withMyClassLoader {
    var tempTarFile: File = null
    try {
      var tarFilePath = config.getString("trustedanalytics.scoring-engine.archive-tar")
      if (tarFilePath.startsWith("hdfs:/")) {
        if (!tarFilePath.startsWith("hdfs://")) {
          val relativePath = tarFilePath.substring(tarFilePath.indexOf("hdfs:") + 6)
          tarFilePath = "hdfs://" + relativePath
        }

        val hdfsFileSystem = try {
          val token = new TapOauthToken(getJwtToken())
          println(s"Successfully retreived a token for user ${token.getUserName}")
          Hdfs.newInstance().createFileSystem(token)
        }
        catch {
          case t: Throwable =>
            t.printStackTrace()
            info("Failed to create HDFS instance using hadoop-library. Default to FileSystem")
            org.apache.hadoop.fs.FileSystem.get(new URI(tarFilePath), new Configuration())
        }
        tempTarFile = File.createTempFile("modelTar", ".tar")
        hdfsFileSystem.copyToLocalFile(false, new Path(tarFilePath), new Path(tempTarFile.getAbsolutePath))
        tarFilePath = tempTarFile.getAbsolutePath
      }
      ModelPublishFormat.read(new File(tarFilePath), this.getClass.getClassLoader.getParent)
    }
    finally {
      FileUtils.deleteQuietly(tempTarFile)
    }
  }

  /**
   * We need an ActorSystem to host our application in and to bind it to an HTTP port
   */
  private def createActorSystemAndBindToHttp(scoringService: ScoringService): Unit = {
    // create the system
    implicit val system = ActorSystem("trustedanalytics-scoring")
    implicit val timeout = Timeout(5.seconds)
    val service = system.actorOf(Props(new ScoringServiceActor(scoringService)), "scoring-service")
    // Bind the Spray Actor to an HTTP Port
    // start a new HTTP server with our service actor as the handler
    IO(Http) ? Http.Bind(service, interface = config.getString("trustedanalytics.atk.scoring.host"), port = config.getInt("trustedanalytics.atk.scoring.port"))
    println("scoring server is running now")
  }

  def getJwtToken(): String = withContext("httpsGetQuery") {

    val query = s"http://${System.getenv("UAA_URI")}/oauth/token"
    val headers = List(("Accept", "application/json"))
    val data = List(("username", System.getenv("FS_TECHNICAL_USER_NAME")), ("password", System.getenv("FS_TECHNICAL_USER_PASSWORD")), ("grant_type", "password"))
    val credentialsProvider = new BasicCredentialsProvider()
    credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(System.getenv("UAA_CLIENT_NAME"), System.getenv("UAA_CLIENT_PASSWORD")))

    // TODO: This method uses Apache HttpComponents HttpClient as spray-http library does not support proxy over https
    val (proxyHostConfigString, proxyPortConfigString) = ("https.proxyHost", "https.proxyPort")
    val httpClient = HttpClientBuilder.create().setDefaultCredentialsProvider(credentialsProvider).build()
    try {
      val proxy = (sys.props.contains(proxyHostConfigString), sys.props.contains(proxyPortConfigString)) match {
        case (true, true) => Some(new HttpHost(sys.props.get(proxyHostConfigString).get, sys.props.get(proxyPortConfigString).get.toInt))
        case _ => None
      }

      val config = {
        val cfg = RequestConfig.custom().setConnectTimeout(30)
        if (proxy.isDefined)
          cfg.setProxy(proxy.get).build()
        else cfg.build()
      }

      val request = new HttpPost(query)
      val nvps = new JArrayList[BasicNameValuePair]
      data.foreach { case (k, v) => nvps.add(new BasicNameValuePair(k, v)) }
      request.setEntity(new UrlEncodedFormEntity(nvps))

      for ((headerTag, headerData) <- headers)
        request.addHeader(headerTag, headerData)
      request.setConfig(config)

      var response: Option[CloseableHttpResponse] = None
      try {
        response = Some(httpClient.execute(request))
        val inputStream = response.get.getEntity().getContent
        val result = scala.io.Source.fromInputStream(inputStream).getLines().mkString("\n")
        println(s"Response From UAA Server is $result")
        result.parseJson.asJsObject().getFields("access_token") match {
          case values => values(0).asInstanceOf[JsString].value
        }
      }
      catch {
        case ex: Throwable =>
          error(s"Error executing request ${ex.getMessage}")
          // We need this exception to be thrown as this is a generic http request method and let caller handle.
          throw ex
      }
      finally {
        if (response.isDefined)
          response.get.close()
      }
    }
    finally {
      httpClient.close()
    }
  }(null)

}

