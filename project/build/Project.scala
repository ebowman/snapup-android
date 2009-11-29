import sbt._

class TheProject(info: ProjectInfo) extends AndroidProject(info: ProjectInfo) {    
  override def androidSdkPath = Path.fromFile("/Users/nathan/Programming/android-sdk-mac/")
  override def androidPlatformName="android-2.0"

  val databinder_net = "databinder.net repository" at "http://databinder.net/repo"
  lazy val meetup = "net.databinder" %% "dispatch-meetup" % "0.6.4-SNAPSHOT"
  override def proguardOption = """
    |-keep class dispatch.** { 
    |  public scala.Function1 *();
    |}
    |-keep class ** extends dispatch.Builder {
    |  public ** product();
    |}
""".stripMargin
}