package com.gwz.dockerexp

import akka.http.Http
import akka.http.model._
import akka.http.model.HttpMethods._
import akka.stream.scaladsl.{Flow,Sink,Source}
import akka.stream.ActorFlowMaterializer
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor._
import com.typesafe.config.{ Config, ConfigFactory }
import scala.sys.process._

trait DocSvr {
	implicit var system:ActorSystem = null
	def appArgs:Array[String] = Array.empty[String]
	var name = ""
	var myHttpUri = ""
	var akkaUri:Address = null
	var myActor:ActorRef = null

	def init() {
		NodeConfig parseArgs appArgs map{ nc =>
			val c:NodeConfig = nc.copy(hostIP = this.hostIP())
			name = c.getString("dkr.name")
			val httpPort = c.getInt("http.port")
			myHttpUri = "http://"+hostIP()+":"+httpPort+"/"
			system = ActorSystem( "dockerexp", c)

			akkaUri = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
			println("AKKA: "+akkaUri)

			myActor = system.actorOf(Props(new TheActor(this)), "dockerexp")

			HttpService(this, myHostname, httpPort)
		}
	}

	def hostIP() = java.net.InetAddress.getByName("dockerhost").getHostAddress.toString
}

case class HttpService(svr:DocSvr, iface:String, port:Int) {

	implicit val system = svr.system
	implicit val materializer = ActorFlowMaterializer()
	implicit val t:Timeout = 15.seconds

	println("HTTP Service on port "+port)

	val requestHandler: HttpRequest ⇒ HttpResponse = {
		case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  => HttpResponse(entity = s"""{"resp":"${svr.name} says pong"}""")
		case HttpRequest(GET, Uri.Path("/ip"), _, _, _)  => HttpResponse(entity = s"""{"resp":"${svr.name} says ${svr.hostIP()}"}""")
		case _: HttpRequest => HttpResponse(404, entity = "Unknown resource!")
	}

	val serverSource: Source[Http.IncomingConnection, Future[Http.ServerBinding]] = 
		Http(system).bind(interface = iface, port = port)
	val bindingFuture: Future[Http.ServerBinding] = serverSource.to(Sink.foreach { connection =>
		connection handleWithSyncHandler requestHandler
		// this is equivalent to
		// connection handleWith { Flow[HttpRequest] map requestHandler }
	}).run()
}

object Go extends App with DocSvr {
	override def appArgs = args
	init()
}