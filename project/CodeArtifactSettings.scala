import sbt._
import sbt.Keys._

object CodeArtifactSettings {
  val codeArtifactRealm = "vayla-viite/viite_maven_packages"
  val codeArtifactResolver = "vayla-viite--viite_maven_packages"
  val codeArtifactDomain = "vayla-viite-783354560127.d.codeartifact.eu-west-1.amazonaws.com"
  val awsCodeArtifactRepoURL: String = "https://vayla-viite-783354560127.d.codeartifact.eu-west-1.amazonaws.com/maven/viite_maven_packages/"
  val awsCodeArtifactAuthToken: String = scala.sys.env.getOrElse("CODE_ARTIFACT_AUTH_TOKEN", null)

  // check if the CODE_ARTIFACT_AUTH_TOKEN is set
  private def isCodeArtifactConfigured: Boolean =
    sys.env.get("CODE_ARTIFACT_AUTH_TOKEN").exists(_.nonEmpty)

  private def withFallbackUrl(dependency: ModuleID, url: String): ModuleID = {
    if (isCodeArtifactConfigured) dependency else dependency.from(url)
  }

  // Add fallback URLs for dependencies that are not available in Maven Central
  // These will be used if the CodeArtifact token is not set
  def withFallbackUrls(dependencies: Seq[ModuleID]): Seq[ModuleID] = {
    dependencies.map {
      case dep if dep.organization == "org.geotools" =>
        val artifactId = dep.name
        val version = dep.revision
        withFallbackUrl(dep, s"https://repo.osgeo.org/repository/release/org/geotools/$artifactId/$version/$artifactId-$version.jar")
      case dep if dep.organization == "jgridshift" =>
        withFallbackUrl(dep, s"https://repo.osgeo.org/repository/release/jgridshift/jgridshift/${dep.revision}/jgridshift-${dep.revision}.jar")
      case dep => dep
    }
  }

  def getLocalLibSettings() = {
    if (isCodeArtifactConfigured) {
      settingsLibsFromCodeArtifact // if the token is set, use the CodeArtifact resolver
    } else {
      settingsLibsFromDefaultresolvers // if the token is not set, use the default resolvers
    }
  }

  val settingsLibsFromCodeArtifact: Seq[Def.Setting[_]] = Seq(
    resolvers        ++= {  Seq("CodeArtifact" at awsCodeArtifactRepoURL)    }, // if the token is set, use the CodeArtifact resolver
    credentials      ++= {  Seq(Credentials(codeArtifactRealm, codeArtifactDomain, "aws", sys.env("CODE_ARTIFACT_AUTH_TOKEN")))  },
    externalResolvers := {  Resolver.withDefaultResolvers(resolvers.value, mavenCentral = false)  }
  )

  val settingsLibsFromDefaultresolvers: Seq[Def.Setting[_]] = Seq(
    resolvers        ++= {  Seq(Classpaths.typesafeReleases)  }, // if the token is not set, use the default resolvers
    credentials      ++= {  Seq.empty  },
    externalResolvers := {  Resolver.withDefaultResolvers(resolvers.value)    }
  )
}
