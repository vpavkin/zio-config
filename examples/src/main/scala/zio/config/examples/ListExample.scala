package zio.config.examples

import zio.config._, ConfigDescriptor._
import zio.DefaultRuntime
import zio.config.PropertyTree, PropertyTree.{ Leaf, Record }

// List works quite nicely if the source is typesafe HOCON. Refer TypesafeConfigHoconExample.scala to get an idea.
object ListExample extends App {
  final case class PgmConfig(a: String, b: List[String])

  val multiMap =
    Map(
      "xyz"     -> singleton("something"),
      "regions" -> ::("australia", List("canada", "usa"))
    )

  val config: ConfigDescriptor[String, String, PgmConfig] =
    (string("xyz") |@| list(string("regions")))(PgmConfig.apply, PgmConfig.unapply)

  val runtime = new DefaultRuntime {}

  val tree =
    ConfigSource.fromMultiMap(multiMap)

  val resultFromMultiMap =
    read(config from ConfigSource.fromMultiMap(multiMap))

  println(resultFromMultiMap)

  val expected =
    PgmConfig("something", List("australia", "canada", "usa"))

  assert(
    resultFromMultiMap ==
      Right(
        PgmConfig("something", List("australia", "canada", "usa"))
      )
  )

  assert(
    write(config, expected) ==
      Right(
        PropertyTree.Sequence(
          List(
            Record(
              Map("xyz" -> Leaf("something"))
            ),
            PropertyTree.Sequence(
              List(
                Record(Map("regions" -> Leaf("australia"))),
                Record(Map("regions" -> Leaf("canada"))),
                Record(Map("regions" -> Leaf("usa")))
              )
            )
          )
        )
      )
  )

  // Keep a note that, handling list in a flattened map like structure may not be what you need to do, have a look at TypesafeConfigHoconExample.
}