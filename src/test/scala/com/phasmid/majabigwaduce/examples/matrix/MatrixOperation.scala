/*
 * Copyright (c) 2018. Phasmid Software
 */

package com.phasmid.majabigwaduce.examples.matrix

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.util.Timeout
import com.phasmid.majabigwaduce._
import com.phasmid.majabigwaduce.examples.CountWords.getTimeout
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Random, Success, Try}

/**
  * This case class is a framework for performing matrix operations.
  * It requires an key-mapping function whose purpose is to partition the work (for parallelization) according to row and/or column numbers.
  * It also requires an actor system, a logger, configuration, etc. (these are implicit parameters).
  *
  * CONSIDER moving the main features of this code into the main Scala code.
  *
  * @param keyFunc the key-mapping function.
  * @param system  (implicit) the actor system.
  * @param logger  (implicit) the logger.
  * @param config  (implicit) the configuration.
  * @param timeout (implicit) the timeout value.
  * @param ec      (implicit) the execution context.
  * @tparam X the underlying type of the matrices (requires evidence of Numeric[X]).
  */
case class MatrixOperation[X: Numeric](keyFunc: Int => Int)(implicit system: ActorSystem, logger: LoggingAdapter, config: Config, timeout: Timeout, ec: ExecutionContext) extends ((Seq[Seq[X]], Seq[X]) => Future[Seq[X]]) {

  self =>

  type XS = Seq[X]
  type Row = (Int, XS)
  type Element = (Int, X)
  type Vector = Map[Int, X]

  override def apply(xss: Seq[XS], ys: XS): Future[XS] = {
    val actors: Actors = Actors(implicitly[ActorSystem], implicitly[Config])
    implicit object zeroVector$$ extends Zero.VectorZero[X]
    val s1 = MapReduceFirstFold[Row, Int, Element, Vector](
      { case (i, xs) => FP.sequence(keyFunc(i) -> FP.sequence(i -> MatrixOperation.dot(xs, ys))) },
      { case (v, (i, x)) => v + (i -> x) }
    )(actors, timeout)
    val r = Reduce[Int, Vector, Vector] { case (s, t) => s ++ t }
    val mr = s1 | r
    val z = (xss zipWithIndex) map (_ swap)
    // CONSIDER doing this as another stage of map-reduce (or part of r stage).
    FP.flatten(for (q <- mr(z)) yield mapVectorToXS(q, xss.length))
  }

  def product(xss: Seq[XS], yss: Seq[XS]): Future[Seq[XS]] = {
    // CONSIDER doing just one transpose: that of xss
    val q: Seq[Future[XS]] = for (ys <- Matrix2(yss).transpose) yield apply(xss, ys)
    Future.sequence(q) map (Matrix2(_).transpose)
  }

  private def mapVectorToXS(q: Vector, n: Int): Try[XS] = {
    val keys = q.keySet
    if (keys.size == n) {
      val z = for (i <- 0 until n) yield q(i)
      Try(z.foldLeft(Seq[X]())((b, x) => b :+ x))
    }
    else Failure(MapReduceException(s"mapVectorToXS: incorrect count: ${keys.size}, $n"))
  }
}

/**
  * This object includes methods that do not themselves need to parallelize their operations.
  * Instead, these methods are invoked in the (remote) actors which are defined in the MatrixOperation case class.
  *
  * Additionally, this object is an App which will test the multiplication of matrices of size defined in the configuration file.
  *
  * CONSIDER eliminating this App aspect of the object.
  */
object MatrixOperation extends App {

  // TODO This is appears to be redundant
  trait DoubleZero$ extends Zero[Double] {
    def zero: Double = 0
  }

  implicit object DoubleZero$ extends DoubleZero$

  /**
    * Guard method (currently not used).
    *
    * CONSIDER moving to FP.
    *
    * @param g a function T => Try[T]
    * @param f a function T => R
    * @param t the input value.
    * @tparam T the input type.
    * @tparam R the output type.
    * @return a value of R, wrapped in Try.
    */
  def guard[T, R](g: T => Try[T], f: T => R)(t: T): Try[R] = g(t) map f

  /**
    * Guard method (currently not used).
    *
    * CONSIDER moving to FP.
    *
    * @param g  a function (T1, T2) => Try[(T1, T2)]
    * @param f  a function (T1, T2) => R
    * @param t1 a T1 value.
    * @param t2 a T2 value.
    * @tparam T1 the type of the t1 parameter.
    * @tparam T2 the type of the t2 parameter.
    * @tparam R  the result type.
    * @return a value of R, wrapped in Try.
    */
  def guard2[T1, T2, R](g: (T1, T2) => Try[(T1, T2)], f: (T1, T2) => R)(t1: T1, t2: T2): Try[R] = g(t1, t2) map f.tupled

  /**
    * Method to make a compatibility check on two vectors (not currently used).
    * The result is successful if the vectors are of the same (non-zero) size.
    *
    * CONSIDER moving to FP.
    *
    * @param as a vector of As.
    * @param bs a vector of As.
    * @tparam A the underlying type of the vectors.
    * @return a tuple of the two vectors, all wrapped in Try.
    */
  def checkCompatible[A](as: Seq[A], bs: Seq[A]): Try[(Seq[A], Seq[A])] = if (as.size == bs.size && as.nonEmpty) Success((as, bs)) else Failure(IncompatibleLengthsException(as.size, bs.size))

  /**
    * Method to make a compatibility check on a vector and a 2-matrix (not currently used).
    * The result is successful if the vectors are of the same (non-zero) size.
    *
    * CONSIDER moving to FP.
    *
    * @param as  a vector of As, represented as a Seq[A].
    * @param ass a 2-matrix of As, represented as a Seq[Seq[A]\].
    * @tparam A the underlying type of the elements.
    * @return a tuple of the vector and the transpose of the 2-matrix, all wrapped in Try.
    */
  def checkCompatibleX[A](as: Seq[A], ass: Seq[Seq[A]]): Try[(Seq[A], Seq[Seq[A]])] = {
    val transpose = ass.transpose
    if (as.size == transpose.size && as.nonEmpty) Success((as, transpose)) else Failure(IncompatibleLengthsException(as.size, transpose.size))
  }

  /**
    * Method to yield the dot product of two vectors.
    *
    * @param as the first vector.
    * @param bs the second vector.
    * @tparam X the underlying type of both vectors (must be numeric).
    * @return the dot product of as and bs, wrapped in Try.
    */
  def dot[X: Numeric](as: Seq[X], bs: Seq[X]): Try[X] = {
    def product(ab: (X, X)): X = implicitly[Numeric[X]].times(ab._1, ab._2)

    if (as.length == bs.length)
      Try(((as zip bs) map product).sum)
    else
      Failure(IncompatibleLengthsException(as.length, bs.length))
  }

  /**
    * Method to yield the product of a vector and a 2-matrix.
    *
    * @param as  the vector expressed as a Seq[X].
    * @param bss the 2-matrix expressed as a Seq[Seq[X]\].
    * @tparam X the underlying type of the elements.
    * @return a vector which is the product of as and bss (wrapped in Try).
    */
  def product[X: Numeric](as: Seq[X], bss: Seq[Seq[X]]): Try[Seq[X]] = FP.sequence(for (bs <- bss.transpose) yield dot(as, bs))

  def getException[X](t: (Seq[X], Seq[X])): Try[X] = Failure(IncompatibleLengthsException(t._1.size, t._2.size))

  implicit val config: Config = ConfigFactory.load.getConfig("Matrix")
  implicit val system: ActorSystem = ActorSystem(config.getString("name"))
  implicit val timeout: Timeout = getTimeout(config.getString("timeout"))
  val rows = config.getInt("rows")
  val cols = config.getInt("columns")
  val modulus = config.getInt("modulus")
  implicit val logger: LoggingAdapter = system.log

  import ExecutionContext.Implicits.global

  val op: MatrixOperation[Double] = MatrixOperation(x => x % modulus)

  def row(i: Int): Seq[Double] = {
    val r = new Random(i)
    (LazyList.from(0) take cols) map (_ => r.nextDouble())
  }

  val matrix: Seq[Seq[Double]] = LazyList.tabulate(rows)(row)
  val vector: Seq[Double] = row(-1)
  val isf: Future[Seq[Double]] = op(matrix, vector)
  Await.result(isf, 10.minutes)
  isf foreach println
  system.terminate()
}
