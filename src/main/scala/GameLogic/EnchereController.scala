package GameLogic

import GameLogic.Enchere._
import akka.pattern.ask
import UI.Router.{ReturnResults, AwaitSurCoinche, AwaitBid}
import scala.concurrent.duration._
import scala.concurrent.{future,Future, Await}
import akka.util.Timeout
import scala.language.postfixOps
import GameLogic.Bot.BotTrait
import GameLogic.Joueur._
import scala.Some
import GameLogic.Enchere.Undef

object EnchereController{
  val PlayerTypeChangeException:Exception = new Exception
  var coincheTimeout = Duration(5,SECONDS)
  var surCoincheTimeout = Duration(5,SECONDS)
}

class EnchereController(implicit Partie:Partie){
  import EnchereController._

  implicit val timeout = new Timeout(10 minutes)

  val Router = Partie.Reader.router

  var nbPasse = 0
  var listEnchere:List[Enchere] = List()
  def current:Option[Enchere] = listEnchere.headOption

  def couleur = current.fold(Undef:Couleur)(_.couleur)
  def contrat = current.fold(70)(_.contrat)
  def id = current.fold(Joueur.Undef:Joueur.Position)(_.id)
  def coinche = current.fold(Normal:Enchere.Coinche)(_.coinche)
  def equipe = id match {
    case Nord | Sud => NordSud
    case Est | Ouest => EstOuest
    case Joueur.Undef => UndefEquipe
  }

  /**
   *
   * @param joueur Joueur qui a annoncé
   * @param valeur Valeur de l'annonce
   * @return true si : c'est a joueur de parler
   *                   valeur est superieur a l'enchere courante
   *                   valeur est legal (80,...,160,250 ou 400)
   *                   l'enchere courante n'est pas coinché
   *         false sinon
   */
  def annonceLegal(joueur:Joueur,valeur:Int):Boolean = {
    valeur>contrat && ( valeur== 250 || valeur== 400 || (valeur%10 == 0 && valeur< 170 && valeur > 70)) &&
    Partie.currentPlayer == joueur && coinche == Normal
  }

  /**
   *
   * @param j le joueur qui passe
   * @return true si : c'est a j de parler
   *                   l'enchere courante n'est pas coinche
   */
  def passeLegal(j:Joueur):Boolean = {
    println(s"passe de $j pour $current")
    Partie.currentPlayer == j && coinche == Normal
  }

  /**
   *
   * @param j le joueur qui a coinché
   * @return true si : j n'est pas dans l'equipe qui tient l'enchere courante
   *                   l'enchere courante est superieur a 80
   *                   l'enchere courante n'a pas deja ete coinche
   */
  def coincheValid(j:Joueur) = {
    contrat > 80 && equipe != j.equipe && current.exists(_.coinchable) && nbPasse == 0
  }

  /**
   *
   * @param j le joueur qui a surcoinche
   * @return true si : j est dans l'equipe de l'enchere courante
   *                   l'enchere courante a etait coinche
   */
  def surCoincheValid(j:Joueur) = current.exists(_.surCoinchable) && equipe == j.equipe

  def enchereCoinche(e:Enchere):Enchere = e.copy(coinche = Coinche)

  def enchereSurCoinche(e:Enchere):Enchere = e.copy(coinche = SurCoinche)

  def effectuerEnchere():Option[Enchere] = {
    import UI.Reader._
    def readMessage:Option[Enchere] = {
      val card = try {Await.result(Router ? AwaitBid,Duration.Inf)}
                 catch {case t:java.util.concurrent.TimeoutException => {Router ! StopWaiting; None}}
      card match {
        case Coinche(j) if coincheValid(j) => Some(enchereCoinche(current.get))
        case SurCoinche(j) if surCoincheValid(j) => Some(enchereSurCoinche(current.get))
        case Passe(j) if passeLegal(j) => None
        case Bid(j,couleur,valeur) if annonceLegal(j,valeur) => Some(new Enchere(couleur,valeur,j.id,j.nom))
        case Bid(j,_,_) if j == Partie.currentPlayer => {Partie.Printer.annonceImpossible;readMessage}
        case StopGame => throw new InterruptedException
        case PlayerTypeChange => throw PlayerTypeChangeException
        case _ => readMessage
      }
    }
    readMessage
  }

  def surCoinche:Option[Enchere] = {
    Partie.Printer.printCoinche()
    Router ! AwaitSurCoinche
    Thread.sleep(EnchereController.surCoincheTimeout.toMillis) // X secondes pour surcoincher
    val listSurCoinche = Await.result((Router ? ReturnResults).mapTo[List[Joueur]], 10 seconds)
    if (listSurCoinche.exists(surCoincheValid)) {
      val surCoinche = enchereSurCoinche(current.get)
      listEnchere = surCoinche :: listEnchere
      Some(enchereSurCoinche(current.get))
    } else None
  }

  // Receiving a PlayerTypeChangeException means a human player was replaced by a bot
  // We stop waiting for input and then asks the bot for a bid
  def getEnchere: Option[Enchere] = {
    Partie.Printer.tourJoueurEnchere(Partie.currentPlayer)
    def aux:Option[Enchere] = try{
      Partie.currentPlayer match {
        case b:BotTrait => b.effectuerEnchere(listEnchere)
        case j:Joueur => effectuerEnchere()
      }
    } catch {case `PlayerTypeChangeException` => aux}
    aux
  }

  /**
   *
   * @return Option sur enchere : (couleur,contrat,id,coinche)
   *         id : 0 pour sud, 1 pour ouest, 2 pour nord, 3 pour est
   *         coinche : 1 = pas de coinche, 2 = coinché, 4 = contré
   */
  def enchere():Option[Enchere] = {


    // On reinitialise les variables globales
    nbPasse = 0
    listEnchere = List()

    Partie.state = Partie.State.bidding


    // Boucle principale lors des encheres
    while ( (nbPasse < 3)                      // apres 3 passes on finit les encheres
      || (current == None && nbPasse == 3)){   // sauf s'il n'y a pas eu d'annonce,auquel cas on attend le dernier joueur
      if (current.exists(_.coinche == Coinche)) {
        surCoinche
        nbPasse = 4
      } else {
        val enchere = getEnchere
        if (enchere.isEmpty) nbPasse=nbPasse+1
        else {
          //une enchere a etait faite, on remet le nombre de passe a zero
          nbPasse=0
          listEnchere = enchere.get :: listEnchere
        }
        Partie.Printer.printEnchere(enchere)
        Partie.currentPlayer = Partie.nextPlayer(Partie.currentPlayer)
      }
    }

    Router ! UI.Router.Normal
    Partie.state = Partie.State.running

    current
  }
}
