val scala3Version = "3.4.0"
val zioVersion = "2.0.20"
val zioConfigVersion = "4.0.1"
val nettyVersion = "4.1.104.Final"

lazy val root = project
  .in(file("."))
  .settings(
    name := "connection-manager",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    
    libraryDependencies ++= Seq(
      // ZIO Core
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-config" % zioConfigVersion,
      "dev.zio" %% "zio-config-typesafe" % zioConfigVersion,
      "dev.zio" %% "zio-config-magnolia" % zioConfigVersion,
      "dev.zio" %% "zio-logging" % "2.1.16",
      "dev.zio" %% "zio-logging-slf4j" % "2.1.16",
      "dev.zio" %% "zio-json" % "0.6.2",
      
      // Netty
      "io.netty" % "netty-all" % nettyVersion,
      
      // Redis (Lettuce)
      "io.lettuce" % "lettuce-core" % "6.3.2.RELEASE",
      
      // HTTP (zio-http)
      "dev.zio" %% "zio-http" % "3.0.0-RC4",
      
      // Kafka
      "org.apache.kafka" % "kafka-clients" % "3.6.1",
      
      // Config
      "com.typesafe" % "config" % "1.4.3",
      
      // Logging
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      
      // Testing
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-test-magnolia" % zioVersion % Test
    ),
    
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )
