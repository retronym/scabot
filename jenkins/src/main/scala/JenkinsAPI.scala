package scabot
package jenkins

import spray.http.BasicHttpCredentials

import scala.concurrent.Future

trait JenkinsApi extends JenkinsApiTypes with JenkinsJsonProtocol with JenkinsApiActions { self: core.Service => }

trait JenkinsApiTypes {
  case class Job(name: String,
                 description: String,
                 nextBuildNumber: Int,
                 builds: List[Build],
                 queueItem: Option[QueueItem],
                 lastBuild: Build,
                 firstBuild: Build)

  case class Build(number: Int, url: String) extends Ordered[Build] {
    def num: Int = number.toInt

    def compare(that: Build) = that.num - this.num
  }

  // value is optional: password-valued parameters hide their value
  case class Param(name: String, value: Option[String]) {
    override def toString = "%s -> %s" format(name, value)
  }

  case class Action(parameters: Option[List[Param]]) {
    override def toString = "Parameters(%s)" format (parameters mkString ", ")
  }

  case class BuildStatus(number: Int,
                         result: String,
                         building: Boolean,
                         duration: Long,
                         actions: List[Action],
                         url: String) {

    assert(!(building && queued), "Cannot both be building and queued.")

    def friendlyDuration = {
      val seconds = try {
        duration.toInt / 1000
      } catch {
        case x: Exception => 0
      }
      "Took " + (if (seconds <= 90) seconds + " s." else (seconds / 60) + " min.")
    }

    def queued = false

    def isSuccess = !building && result == "SUCCESS"

    override def toString = s"Build $number: ${if (building) "BUILDING" else result} $friendlyDuration ($url)."
  }

  case class Queue(items: List[QueueItem])

  case class QueueItem(actions: List[Action], task: Task, id: Int) {
    def jobName = task.name

    // the url is fake but needs to be unique
    def toStatus = new BuildStatus(0, s"Queued build for ${task.name } id: ${id}", false, -1, actions, task.url + "/queued/" + id) {
      override def queued = true
    }
  }

  case class Task(name: String, url: String)

  // for https://wiki.jenkins-ci.org/display/JENKINS/Notification+Plugin
  case class JobState(name: String, url: String, build: BuildState)
  //  STARTED, COMPLETED, FINALIZED;
  case class BuildState(full_url: String, number: Int, phase: String, result: String, url: String, displayName: String,
                        scm: ScmState, parameters: Map[String, String], log: String)
  case class ScmState(url: String, branch: String, commit: String)
}


import spray.json.{RootJsonFormat, DefaultJsonProtocol}

// TODO: can we make this more debuggable?
trait JenkinsJsonProtocol extends JenkinsApiTypes with DefaultJsonProtocol { private type RJF[x] = RootJsonFormat[x]
  implicit lazy val _fmtJob         : RJF[Job        ] = jsonFormat7(Job)
  implicit lazy val _fmtBuild       : RJF[Build      ] = jsonFormat2(Build)
  implicit lazy val _fmtParam       : RJF[Param      ] = jsonFormat2(Param)
  implicit lazy val _fmtActions     : RJF[Action     ] = jsonFormat1(Action)
  implicit lazy val _fmtBuildStatus : RJF[BuildStatus] = jsonFormat6(BuildStatus)
  implicit lazy val _fmtQueue       : RJF[Queue      ] = jsonFormat1(Queue)
  implicit lazy val _fmtQueueItem   : RJF[QueueItem  ] = jsonFormat3(QueueItem)
  implicit lazy val _fmtTask        : RJF[Task       ] = jsonFormat2(Task)
  implicit lazy val _fmtJobState    : RJF[JobState   ] = jsonFormat3(JobState)
  implicit lazy val _fmtBuildState  : RJF[BuildState ] = jsonFormat9(BuildState)
  implicit lazy val _fmtScmState    : RJF[ScmState   ] = jsonFormat3(ScmState)
}

trait JenkinsApiActions extends JenkinsJsonProtocol with core.HttpClient { self: core.Service =>

  class JenkinsConnection(val host: String, user: String, token: String) {

    import spray.http.{GenericHttpCredentials, Uri}
    import spray.httpx.SprayJsonSupport._
    import spray.client.pipelining._

    private implicit def connection = setupConnection(host, BasicHttpCredentials(user, token))

    def api(rest: String) = Uri("/" + rest)

    def buildJob(name: String, params: Map[String, String] = Map.empty) =
      p[String](Post(if (params.isEmpty) api(name / "build")
      else api("job" / name / "buildWithParameters") withQuery (params)))

    def buildStatus(name: String, buildNumber: Int) =
      p[BuildStatus](Get(api("job" / name / buildNumber / "api/json")))

    /** A traversable that lazily pulls build status information from jenkins.
      *
      * Only statuses for the specified job (`job.name`) that have parameters that match all of `expectedArgs`
      */
    def buildStatusForJob(job: String, expectedArgs: Map[String, String]): Future[Stream[BuildStatus]] = {
      def queuedStati(q: Queue) = q.items.toStream.filter(_.jobName == job).map(_.toStatus)
      def reportedStati(info: Job) = Future.sequence(info.builds.sorted.toStream.map(b => buildStatus(job, b.number)))

      // hack: retrieve queued jobs from queue/api/json
      // queued items must come first, they have been added more recently or they wouldn't have been queued
      val all = for {
        queued   <- p[Queue](Get(api("queue/api/json"))).map(queuedStati)
        reported <-   p[Job](Get("job" / job / "api/json")).flatMap(reportedStati)
      } yield queued ++ reported

      all.map(_.filter { status =>
        val paramsForExpectedArgs = status.actions.flatMap(_.parameters).flatten.collect {
          case Param(n, Some(v)) if expectedArgs.isDefinedAt(n) => (n, v)
        }.toMap

        paramsForExpectedArgs == expectedArgs
      })
    }
  }

}
