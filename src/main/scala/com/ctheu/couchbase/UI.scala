package com.ctheu.couchbase

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling._
import akka.stream._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.couchbase.client.java.{AsyncBucket, CouchbaseCluster}
import com.couchbase.client.java.document.JsonDocument
import de.heikoseeberger.akkasse.ServerSentEvent
import play.api.libs.json.{JsValue, Json, Writes}
import rx.Observable

import scala.concurrent.Promise
import scala.language.postfixOps

object UI {

  import CouchbaseGraph._

  def route()(implicit sys: ActorSystem, mat: Materializer): Route = {
    import akka.http.scaladsl.server.Directives._
    import de.heikoseeberger.akkasse.EventStreamMarshalling._

    import concurrent.duration._


    implicit val ec = sys.dispatcher
    implicit val keysJson = Json.writes[KeyWithCounters[String]]
    implicit val combinaisonJson = Json.writes[Combinaison[String, String, String]]
    implicit val tupleJson = Json.writes[KeyWithExpiry]
    implicit val keysExpiryJson = Json.writes[KeyWithCounters[KeyWithExpiry]]

    implicit val combinaisonJsonWithExpiry = Json.writes[Combinaison[KeyWithExpiry, String, String]]
    implicit val durationMarshaller = Unmarshaller.strict[String, FiniteDuration](s => FiniteDuration(s.toInt, "ms"))

    val DEFAULT_DURATION: FiniteDuration = 200 millis
    val DEFAULT_COUNT = 10

    val connectionsCache = collection.mutable.Map[(String, String), AsyncBucket]()

    path("documents" / """[-a-z0-9\._]+""".r / """[-a-z0-9\._]+""".r / """[-a-zA-Z0-9\._:]+""".r) { (host, bucket, key) =>
      get {
        complete {
          val b = connectionsCache.getOrElseUpdate((host, bucket), CouchbaseCluster.create(host).openBucket(bucket).async())
          val doc: JsonDocument = b.get(key).toBlocking.last()
          HttpEntity(doc.content().toString)
        }
      }
    } ~ path("events" / """[-a-z0-9\._]+""".r / """[-a-z0-9_]+""".r) { (host, bucket) =>
      get {
        parameters('interval.as[FiniteDuration] ? DEFAULT_DURATION, 'n.as[Int] ? DEFAULT_COUNT) { (interval, nLast) =>
          complete {
            val promise = Promise[(SourceQueueWithComplete[KeyWithExpiry], SourceQueueWithComplete[String], SourceQueueWithComplete[String])]()
            promise.future.foreach { case (m, d, e) => CouchbaseSource.fill(host, bucket, m, d, e) }

            CouchbaseGraph.source(host, bucket, interval, nLast)
              .map { case (m: KeyWithCounters[KeyWithExpiry], d: KeyWithCounters[String], e: KeyWithCounters[String]) => ServerSentEvent(Json.toJson(Combinaison(m, d, e)).toString()) }
              .keepAlive(1 second, () => ServerSentEvent.heartbeat)
              .mapMaterializedValue { x => promise.trySuccess(x); x }
          }
        }
      }
    } ~ path("ui" / """[-a-z0-9\._]+""".r / """[-a-z0-9_]+""".r) { (host, bucket) =>
      get {
        parameter('interval.as[Int] ? DEFAULT_DURATION, 'n.as[Int] ? DEFAULT_COUNT) { (interval, n) =>
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
            s"""
               |<html>
               |<head>
               |<style>
               |.type { display: flex; }
               |#panels { display: flex; }
               |.type div { display: flex; align-items: center; justify-content: center; padding: 10px; flex-direction: column; }
               |.type span { font-weight: bold; font-size: 20px; }
               |#right { flex: 1; margin: 10px; padding: 20px; background: rgba(0,0,0,.1); border: 1px solid #ccc; }
               |</style>
               |<link rel="stylesheet" href="//cdnjs.cloudflare.com/ajax/libs/highlight.js/9.10.0/styles/idea.min.css">
               |</head>
               |<body>
               |<h1>Bucket: $bucket</h1>
               |<div id="panels">
               |<div id="left">
               |<h3>Mutations</h3>
               |<div class="type"><canvas id="mutation" width="400" height="100"></canvas><div>Total<span id="muttotal"></span></div></div>
               |<h3>Deletions</h3>
               |<div class="type"><canvas id="deletion" width="400" height="100"></canvas><div>Total<span id="deltotal"></span></div></div>
               |<h3>Expirations</h3>
               |<div class="type"><canvas id="expiration" width="400" height="100"></canvas><div>Total<span id="exptotal"></span></div></div>
               |<h3>Last $n documents mutated (key, expiry), earliest to oldest</h3>
               |<pre class="prettyprint" id="lastMutation"></pre>
               |</div>
               |<pre id="right">
               |</pre>
               |</div>
               |<script src="//cdnjs.cloudflare.com/ajax/libs/highlight.js/9.10.0/highlight.min.js"></script>               |
               |<script src="//cdnjs.cloudflare.com/ajax/libs/smoothie/1.27.0/smoothie.min.js"></script>
               |<script>
               |function ch(id) {
               |const chart = new SmoothieChart({millisPerPixel:100,maxValueScale:1.5,grid:{strokeStyle:'rgba(119,119,119,0.43)'}});
               |const series = new TimeSeries();
               |chart.addTimeSeries(series, { strokeStyle: 'rgba(0, 255, 0, 1)', fillStyle: 'rgba(0, 255, 0, 0.2)', lineWidth: 1 });
               |chart.streamTo(document.getElementById(id), 1000);
               |return series;
               |}
               |const mut = ch("mutation")
               |const del = ch("deletion")
               |const exp = ch("expiration")
               |
               |function update(sel, value) { document.getElementById(sel + "total").innerHTML = value; }
               |function show(key) {
               |  document.getElementById("right").innerHTML = "Fetching " + key + " ..."
               |  fetch('/documents/$host/$bucket/' + key)
               |    .then(res => res.json())
               |    .then(doc => {
               |      const block = document.getElementById("right")
               |      block.innerHTML = JSON.stringify(doc, null, 2)
               |      hljs.highlightBlock(block)
               |      block.innerHTML = key + "\\n" + "-".repeat(key.length) + "\\n\\n\\n" + block.innerHTML
               |    })
               |}
               |var source = new EventSource('/events/$host/$bucket?interval=${interval.toMillis}&n=$n');
               |source.addEventListener('message', function(e) {
               |  var data = JSON.parse(e.data);
               |  mut.append(new Date().getTime(), data.mutations.lastDelta); update("mut", data.mutations.total);
               |  del.append(new Date().getTime(), data.deletions.lastDelta); update("del", data.deletions.total);
               |  exp.append(new Date().getTime(), data.expirations.lastDelta); update("exp", data.expirations.total);
               |  document.getElementById("lastMutation").innerHTML = data.mutations.last.reverse().reduce((acc, x) => acc + "<a href=\\"javascript: show('" + x.key + "')\\">" + x.key + "</a> (" + (x.expiry > 0 ? new Date(x.expiry*1000).toISOString() : "0") + ")" + "<br>", "");
               |}, false);
               |</script>
               |</body>
               |</html>
               |
            """.stripMargin
          ))
        }
      }
    }
  }
}
