package mk.coinche
package gamephases

import models._
import Position._

import ui._

import cats.data.StateT
import cats.implicits._
import cats.effect.{IO, Timer}
import com.typesafe.scalalogging.LazyLogging

case class BiddingPhase(
  table: Table,
  firstBidder: Position
)(
  implicit ui: BidRead,
           timer: Timer[IO]
) extends LazyLogging {
  implicit val l = logger

  def run: IO[List[BidType]] = {
    state.iterateUntil { bids =>
      {
        bids.size >= 4 && // give everybody a chance to bid
        bids.take(3).forall(_ == Pass)
      } ||
      {
        bids.headOption.forall { case _: SurCoinchedBid => true; case _ => false }
      }
    }
    .runA(initialState)
    .map { finalBids =>
      logger.info(s"Final bid list ${finalBids.reverse}")
      finalBids.filterNot(_ == Pass)
    }
  }

  private val initialState = (firstBidder, List[BidType]())

  // the list contains all past bids (newest first),
  private val state: StateT[IO, (Position, List[BidType]), List[BidType]] = StateT.apply { case (bidder, previousBids) =>
    logger.trace(s"Going to read bid for position ${bidder}")
    val firstNonPass = previousBids.filterNot(_ == Pass).headOption.getOrElse(Pass) // shitty, but gets around type erasure

    val afterStep: IO[Option[BidType]] = firstNonPass match {
      case c: CoinchedBid =>
        IO.race(
          readPass(bidder),
          readSurCoinche(c)
        ).map(_.left.map(Some(_)).merge)

      case _: SurCoinchedBid => // XXX should be unreachable, how to prove this though?
        IO.pure(None)

      case b: Bid =>
        val normalPlay =
          IO.race(
            readPass(bidder),
            readBid(bidder, Option(b)),
          ).map(_.merge).map(Some(_))

        IO.race(
          normalPlay,
          readCoinche(b, normalPlay)
        ).map(_.merge)

      case Pass =>
        IO.race(
          readPass(bidder),
          readBid(bidder, None)
        ).map(e => Some(e.merge))
    }

    afterStep.map { newBid =>
      val allBids = newBid.fold(previousBids)(_ :: previousBids)
      ((after(bidder), allBids), allBids)
    }
  }

  private def readBid(position: Position, prevValue: Option[Bid]): IO[BidType] = {
    for {
      userInput <- ui.getBid(position)
      bid       =  Bid.validate(position, userInput._1, userInput._2, prevValue)
                      .left.map[UserInputError](InvalidPlay(_))
      retryIf   <- bid.fold(
                     inv => {
                       logger.info(s"${bid} from ${position} is invalid, asking for a new bid")
                       readBid(position, prevValue)
                     },
                     valid => IO.pure(valid)
                   )
    } yield retryIf
  }

  private def readPass(position: Position): IO[Pass.type] = {
    ui.getPass(position)
  }

  // TODO factor those 2 out together?
  private def readCoinche(bid: Bid, normalPlay: IO[Option[BidType]]): IO[Option[BidType]] = {
    ui.getCoinche(bid).flatMap {
      case coincher =>
        if (coincher.sameTeamAs(bid.position)) readCoinche(bid, normalPlay)
        else IO.pure(Some(CoinchedBid(bid)))
    }
  }

  private def readSurCoinche(coinched: CoinchedBid): IO[Option[BidType]] = {
    ui.getSurCoinche(coinched).flatMap {
      case coincher =>
        if (coincher.sameTeamAs(coinched.bid.position)) IO.pure(Some(SurCoinchedBid(coinched)))
        else readSurCoinche(coinched)
    }
  }
}