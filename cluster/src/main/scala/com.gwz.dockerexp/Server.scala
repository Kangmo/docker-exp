package com.gwz.dockerexp

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.HttpMethods._
import akka.stream.scaladsl.{Flow,Sink,Source}
import akka.stream.ActorFlowMaterializer
import akka.util.Timeout
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.collection.JavaConverters._
import scala.util.Try

import akka.actor._
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import scala.sys.process._

// For Akka to work in Docker we must get 2 critical pieces of information:
//    Host's IP (outside the Docker)
//    Akka port (outside the Docker) for dual-binding

trait DocSvr {
	val ssn = java.util.UUID.randomUUID.toString
	val seeds : List[String]
	val ipAndPort = IpAndPort()
	val c = ConfigFactory.load()
		.withValue("akka.remote.netty.tcp.bind-hostname", ConfigValueFactory.fromAnyRef(java.net.InetAddress.getLocalHost().getHostAddress()))
		.withValue("akka.remote.netty.tcp.hostname", ConfigValueFactory.fromAnyRef(ipAndPort.hostIP))
		.withValue("akka.remote.netty.tcp.port", ConfigValueFactory.fromAnyRef(ipAndPort.akkaPort))
	val name = c.getString("settings.name")
	val myHttpUri = s"""http://${c.getString("akka.remote.netty.tcp.bind-hostname")}:${c.getInt("settings.http")}/"""
	implicit val system = ActorSystem( "dockerexp", c )
	val akkaUri = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
	println("AKKA: "+akkaUri)
	val	myActor = system.actorOf(Props(new TheActor(this)), "dockerexp")
	HttpService(this, java.net.InetAddress.getLocalHost().getHostAddress(), c.getInt("settings.http"))
}

case class HttpService(svr:DocSvr, iface:String, port:Int) {

	implicit val system = svr.system
	implicit val materializer = ActorFlowMaterializer()
	implicit val t:Timeout = 15.seconds

	println("HTTP Service on port "+port)

	val requestHandler: HttpRequest ⇒ HttpResponse = {
		case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  => HttpResponse(entity = s"""{"resp":"${svr.name} says pong"}""")
		case HttpRequest(GET, Uri.Path("/uri"), _, _, _)  => HttpResponse(entity = svr.akkaUri.toString)
		case HttpRequest(GET, Uri.Path("/info"), _, _, _)  => {
			val info = "Public IPV4: "+Util.httpGet( "http://169.254.169.254/latest/meta-data/public-ipv4" ) + // <<-- This one for AWS!  Host's IP
				"\nUser: "+System.getProperty("user.name")+
				"\nAkka URI: "+svr.akkaUri+
				"\nHttp URI: "+svr.myHttpUri+
				"\n"
			HttpResponse(entity = info)
		}
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

object Go extends App {
	args[0] match {
		"web"  => WebServer()
		"seed" => SeedServer(args.tail)
		"c1"   => C1Server(args.tail)
		"c2"   => C2Server(args.tail)
	}
}