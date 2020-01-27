package zio.config

import zio.{ IO, ZIO }
import zio.config.ConfigDescriptor.Sequence

private[config] trait ReadFunctions {
  // Read
  final def read[K, V, A](
    configuration: ConfigDescriptor[K, V, A]
  ): IO[ReadErrorsVector[K, V], A] = {
    def loop[V1, B](
      configuration: ConfigDescriptor[K, V1, B],
      paths: Vector[K]
    ): ZIO[Any, Either[ReadErrorsVector[K, V1], ReadFunctions.MissingValuesInList[K, V1, B]], ::[B]] =
      configuration match {
        case ConfigDescriptor.Source(path, source: ConfigSource[K, V1], propertyType: PropertyType[V1, B]) =>
          val results = for {
            value <- source
                      .getConfigValue(paths :+ path)
                      .mapError(
                        t => Left(t): Either[ReadErrorsVector[K, V1], ReadFunctions.MissingValuesInList[K, V1, B]]
                      )
            result <- foreach(value.value) {
                       case Some(value) =>
                         ZIO.fromEither(
                           propertyType
                             .read(value)
                             .fold(
                               r =>
                                 Left(
                                   Left(
                                     singleton(
                                       ReadError
                                         .parseError(paths :+ path, r.value, r.typeInfo): ReadError[Vector[K], V1]
                                     )
                                   ): Either[ReadErrorsVector[K, V1], ReadFunctions.MissingValuesInList[K, V1, B]]
                                 ),
                               e => Right(Some(e))
                             )
                         )
                       case None => ZIO.succeed(None: Option[B])
                     }
          } yield result

          results.flatMap(
            list =>
              if (list.filter(_.isEmpty).size > 0)
                ZIO.fail(
                  Right(
                    ReadFunctions.MissingValuesInList[K, V1, B](list, paths)
                  ): Either[ReadErrorsVector[K, V1], ReadFunctions.MissingValuesInList[K, V1, B]]
                )
              else
                ZIO.succeed({
                  val sList = list.flatMap(_.toList)
                  ::(sList.head, sList.tail)
                })
          )

        case s: Sequence[K, V1, B] @unchecked =>
          val Sequence(config) = s
          loop(config, paths).map(list => singleton(list))

        case ConfigDescriptor.Nested(path, c) =>
          loop(c, paths :+ path)

        case cd: ConfigDescriptor.XmapEither[K, V1, a, B] =>
          val ConfigDescriptor.XmapEither(c, f, _) = cd
          loop(c, paths).mapError(
            t =>
              Left(t.map(_.toMissingValue).merge): Either[
                ReadErrorsVector[K, V1],
                ReadFunctions.MissingValuesInList[K, V1, B]
              ]
          ) flatMap { as =>
            foreach(as)(a => {
              ZIO
                .fromEither(f(a))
                .bimap(
                  err =>
                    Left(
                      singleton(
                        ReadError.fatalError[Vector[K], V1](paths, new RuntimeException(err))
                      )
                    ): Either[ReadErrorsVector[K, V1], ReadFunctions.MissingValuesInList[K, V1, B]],
                  res => res
                )
            })
          }

        // No need to add report on the default value.
        case ConfigDescriptor.Default(c, value) =>
          loop(c, paths).fold(
            _ => singleton(value),
            identity
          )

        case ConfigDescriptor.Describe(c, _) =>
          loop(c, paths)

        case cd: ConfigDescriptor.Optional[K, V, B] @unchecked =>
          val ConfigDescriptor.Optional(c) = cd
          loop(c, paths).either.flatMap({
            case Right(value) =>
              val res: List[Option[B]] = value.map(t => Some(t): Option[B])
              ZIO.succeed(::(res.head, res.tail))
            case Left(r) =>
              r match {
                case Left(value)  => ZIO.succeed(singleton(None))
                case Right(value) => ZIO.succeed(value.list)
              }

          })

        case r: ConfigDescriptor.Zip[K, V1, a, b] @unchecked => {
          val ConfigDescriptor.Zip(left, right) = r
          for {
            res1 <- loop(left, paths).either
            res2 <- loop(right, paths).either
            r <- ZIO
                  .fromEither((res1, res2) match {
                    case (Right(as), Right(bs)) =>
                      Right(as.zip(bs))
                    case (Left(aa), Right(_)) =>
                      Left(
                        Left(aa.map(_.toMissingValue).merge): Either[
                          ReadErrorsVector[K, V1],
                          ReadFunctions.MissingValuesInList[K, V1, (a, b)]
                        ]
                      )
                    case (Right(_), Left(error)) =>
                      Left(
                        Left(error.map(_.toMissingValue).merge): Either[
                          ReadErrorsVector[K, V1],
                          ReadFunctions.MissingValuesInList[K, V1, (a, b)]
                        ]
                      )
                    case (Left(err1), Left(err2)) =>
                      (err1, err2) match {
                        case (Left(v1), Left(v2)) =>
                          Left(
                            Left(concat(v1, v2)): Either[
                              ReadErrorsVector[K, V1],
                              ReadFunctions.MissingValuesInList[K, V1, (a, b)]
                            ]
                          )
                        case (Right(v1), Right(v2)) =>
                          Left(
                            Right(
                              ReadFunctions.MissingValuesInList(
                                {
                                  val result =
                                    v1.list
                                      .zip(v2.list)
                                      .map({
                                        case (Some(v1), Some(v2)) => (v1, v2)
                                        case (Some(v1), None)     => (v1, None.asInstanceOf[b])
                                        case (None, Some(v2))     => (None.asInstanceOf[a], v2)
                                        case (None, None)         => (None.asInstanceOf[a], None.asInstanceOf[b])
                                      })

                                  ::(Some(result.head), result.tail.map(Some(_)))
                                },
                                paths
                              )
                            ): Either[
                              ReadErrorsVector[K, V1],
                              ReadFunctions.MissingValuesInList[K, V1, (a, b)]
                            ]
                          )
                        case (Left(v1), Right(v2)) =>
                          Left(
                            Left(concat(v1, singleton(ReadError.missingValue[Vector[K], V1](paths)))): Either[
                              ReadErrorsVector[K, V1],
                              ReadFunctions.MissingValuesInList[K, V1, (a, b)]
                            ]
                          )
                        case (Right(v1), Left(v2)) =>
                          Left(
                            Left(concat(v2, singleton(ReadError.missingValue[Vector[K], V1](paths)))): Either[
                              ReadErrorsVector[K, V1],
                              ReadFunctions.MissingValuesInList[K, V1, (a, b)]
                            ]
                          )
                      }
                  })
                  .map(list => ::(list.head, list.tail))
          } yield r
        }

        case cd: ConfigDescriptor.OrElseEither[K, V, a, b] @unchecked =>
          val ConfigDescriptor.OrElseEither(left, right) = cd
          loop(left, paths).either
            .flatMap(
              {
                case Right(a) =>
                  ZIO.succeed(::(a.map(r => Left(r): Either[a, b]).head, a.map(r => Left(r): Either[a, b])))

                case Left(lerr) =>
                  loop(right, paths).either.flatMap(
                    {
                      case Right(b) =>
                        ZIO.succeed(::(b.map(r => Right(r): Either[a, b]).head, b.map(r => Right(r): Either[a, b])))
                      case Left(rerr) =>
                        ZIO.fail(
                          Left(
                            concat(lerr.map(_.toMissingValue).merge, rerr.map(_.toMissingValue).merge)
                          ): Either[
                            ReadErrorsVector[K, V1],
                            ReadFunctions.MissingValuesInList[K, V1, B]
                          ]
                        )
                    }
                  )
              }
            )

        case ConfigDescriptor.OrElse(left, right) =>
          loop(left, paths).either
            .flatMap(
              {
                case Right(a) =>
                  ZIO.succeed(a)

                case Left(_) =>
                  loop(right, paths)
              }
            )
      }

    loop(configuration, Vector.empty[K]).either
      .map({
        case Left(value)  => Left(value.map(_.toMissingValue).merge)
        case Right(value) => Right(value)
      })
      .absolve
      .map(_.head)

  }
}

object ReadFunctions {
  final case class MissingValuesInList[K, V, A](list: ::[Option[A]], path: Vector[K]) {
    def toMissingValue: ReadErrorsVector[K, V] =
      singleton(ReadError.missingValue[Vector[K], V](path))
  }
}
