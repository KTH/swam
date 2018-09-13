import swam._
import text._
import runtime._
import formats.DefaultFormatters._
import cats.effect._
import java.nio.file.Paths

val tcompiler = new Compiler[IO]

val engine = SwamEngine[IO]()

def instantiate(p: String): Instance[IO] =
  (for {
    engine <- engine
    m <- engine.compile(tcompiler.stream(Paths.get(p), true))
    i <- m.newInstance()
  } yield i).unsafeRunSync()

def time[T](t: => T): T = {
  val start = System.currentTimeMillis
  val res = t
  val end = System.currentTimeMillis
  println(s"Time: ${end - start}ms")
  res
}

val i = instantiate("examples/fibo.wat")

val naive = i.exports.typed.function1[Long, Long]("naive").unsafeRunSync()
val clever = i.exports.typed.function1[Long, Long]("clever").unsafeRunSync()

println(time(naive(30).unsafeRunSync()))
println(time(clever(30).unsafeRunSync()))
