package mk.coinche
package gamephases

import models._
import Position._
import BidSuit._
import ui._

import cats.effect.IO
import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest._

class BiddingPhaseSpec extends FunSpec with Matchers {
  type I = Option[(BidSuit, Int)]

  case class BidReadFake(bids: List[I]) extends BidRead {
    private var bidIndex = -1
    private var passIndex = -1

    def getBid(p: Position) = {
      bidIndex += 1
      bids.drop(bidIndex).headOption match {
        case Some(Some(b)) => IO.pure(b)
        case _ => IO.never
      }
    }
    def getPass(p: Position) = {
      passIndex += 1
      bids.drop(passIndex).headOption match {
        case Some(Some(_)) => IO.never
        case _ => IO.pure(Pass)
      }
    }

    def getCoinche(b: Bid) = IO.never
    def getSurCoinche(b: CoinchedBid) = IO.never

  }

  val H: BidSuit = Hearts // help type inference

  describe("Bidding") {
    it("should stop after 3 PASS") {
      implicit val input = BidReadFake(
        List(Some(H -> 100), None, None, None)
      )

      val bp = BiddingPhase(Table.empty(Deck.sorted), North)
      val res = bp.run.unsafeRunSync

      res should equal (List(Bid(North, H, 100)))
    }

    it("should let the last player bid even after 3 PASS") {
      implicit val input = BidReadFake(
        List(None, None, None, Some(H -> 100))
      )

      val bp = BiddingPhase(Table.empty(Deck.sorted), North)
      val res = bp.run.unsafeRunSync

      res should equal (List(Bid(East, H, 100)))
    }

    it("accepts quadruple-PASS") {
      implicit val input = BidReadFake(
        List[I]()
      )

      val bp = BiddingPhase(Table.empty(Deck.sorted), North)
      val res = bp.run.unsafeRunSync

      res should equal (List())
    }

    // Commented out, won't work with the List/index system used to fake bids
    //  -> the failed `getBid` is retried with index++, which point to nothing, ie
    //     getBids expects getPass to get it
    //  -> adding an extra valid step does'nt work, as then this one passes but the
    //     following getPass is late by one and find the newly added 'valid bid',
    //     meaning it doesn't send the PASS required to end the test
    //
    // TODO FIXME
    //
    //it("refuse lower bids") {
      //implicit val input = BidReadFake(
        //List(Some(H -> 100), None, Some(H -> 80))
      //)

      //val bp = BiddingPhase(Table.empty(Deck.sorted), North)
      //val res = bp.run.unsafeRunSync

      //res should equal (List(Bid(North, H, 100)))
    //}

    it("accepts increasingly higher bids") {
      implicit val input = BidReadFake(
        List(Some(H -> 100), Some(Spades -> 110), Some(Hearts -> 120), None)
      )

      val bp = BiddingPhase(Table.empty(Deck.sorted), North)
      val res = bp.run.unsafeRunSync

      res should equal (List(Bid(South, H, 120), Bid(West, Spades, 110), Bid(North, H, 100)))
    }

    it("accepts capot/general bids") {
      implicit val input = BidReadFake(
        List(Some(H -> 100), Some(Spades -> 250), Some(Hearts -> 400), None)
      )

      val bp = BiddingPhase(Table.empty(Deck.sorted), North)
      val res = bp.run.unsafeRunSync

      res should equal (List(Bid(South, H, 400), Bid(West, Spades, 250), Bid(North, H, 100)))
    }

    it("accepts NoTrump/AllTrumps bids") {
      implicit val input = BidReadFake(
        List(Some(H -> 100), Some(NoTrumps -> 110), Some(AllTrumps -> 130), None)
      )

      val bp = BiddingPhase(Table.empty(Deck.sorted), North)
      val res = bp.run.unsafeRunSync

      res should equal (List(Bid(South, AllTrumps, 130), Bid(West, NoTrumps, 110), Bid(North, H, 100)))
    }
  }
}