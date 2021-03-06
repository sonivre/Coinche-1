package GameLogic

import scala.concurrent.Await
import UI.Router.AwaitCard
import akka.pattern.ask
import UI.Reader._
import akka.util.Timeout
import scala.concurrent.duration._
import scala.language.postfixOps
import GameLogic.Bot.BotTrait
import GameLogic.Card.{Roi, Dame}
import scala.Some
import UI.Reader.PlayCard
import GameLogic.Enchere.Normal
import GameLogic.Joueur.NordSud

class MainController(implicit Partie:Partie) {

  import Partie._

  val Router = Reader.router

  implicit val timeout = new Timeout(10 minutes)

  val PlayerTypeChangeException = new Exception

  var pli:List[(Joueur,Card)] = Nil

  /**
   * Contient toutes les cartes deja jouées durant cette main
   * Ne contient pas les cartes du pli en train d'etre joué !
   */
  def cartesJouees:List[Card] = cartesJoueesWithPlayer.map(_._2)
  var cartesJoueesWithPlayer:List[(Joueur,Card)] = List()

  /**
   *
   * @param couleurAtout couleur de l'atout durant cette main
   * @param joueur
   * @return Returns true if 'joueur' has belote at 'couleurAtout'
   */
  def hasBelote(couleurAtout:Enchere.Couleur,joueur:Joueur):Boolean = {
    val atouts = joueur.main.filter(_.couleur == couleurAtout)
    // Has the queen               // Has the king
    atouts.exists(_.valeur == Dame) && atouts.exists(_.valeur == Roi)
  }

  def getCard(jouables:List[Card],autres:List[Card]):Card = {
    // play automatically the last card
    if (jouables.length == 1 && autres.isEmpty) jouables(0)
    else {
      val cardList = try {Await.result(Router ? AwaitCard,Duration.Inf)}
                     catch {case t:java.util.concurrent.TimeoutException => {Router ! StopWaiting; None}}
      cardList match {
        case PlayCard(joueur,cards) if joueur == currentPlayer => {
          val c = jouables.find(c => cards.exists(cc => cc.equals(c)))
          c.getOrElse({Printer.cardUnplayable;getCard(jouables,autres)})
        }
        case StopGame => throw new InterruptedException
        case PlayerTypeChange => throw PlayerTypeChangeException
        case e => getCard(jouables,autres)
      }
    }
  }

  /**
   *
   * @param couleurAtout couleur de l'atout
   * @return Score realise par NORD/SUD
   */
  def jouerLaMain(implicit couleurAtout:Enchere.Couleur):Int = {
    // La variable currentPlayer a ete modifie pendant les encheres
    // La variable dealer ne l'a pas ete
    // Sur generale, le joueur prend la main
    var premierJoueur = if (enchereController.contrat == 400) listJoueur.find(_.id == enchereController.id).get else nextPlayer(dealer)
    var tour = 1
    var scoreNS = 0
    capotChute = false; generalChute = false

    cartesJoueesWithPlayer = List()
    belote = listJoueur.find(hasBelote(couleurAtout,_))

    if (printOnlyOnce) Printer.printCardsToAll(couleurAtout)

    //parce que des fois l'imperatif c'est quand meme pratique
    while (tour < 9) {
      currentPlayer = premierJoueur
      // la liste des cartes sur le pli
      pli = List[(Joueur,Card)]()
      var couleurDemande:Option[Enchere.Couleur] = None
      var plusFortAtout:Option[Card] = None
      var joueurMaitre = currentPlayer
      // cartemaitre et plusFortAtout pourrait etre regroupé
      // mais 1/ suppose de refactorise le code, donc fuck off
      //      2/ suppose de checker a chaque fois si carteMaitre est un atout
      var carteMaitre:Option[Card] = None


      // Tant que tout le monde n'a pas joué
      while (pli.length != 4){
        val (jouables,autres) = cartesJouables(currentPlayer.main,couleurDemande,couleurAtout,plusFortAtout,joueurMaitre)

        Printer.tourJoueur(currentPlayer)
        state = State.playing
        if (!printOnlyOnce) Printer.printCards(jouables,autres)

        def getCarteJoue: Card = try {
          currentPlayer match {
            case b:BotTrait => b.getCard(jouables,autres,pli)
            case j:Joueur => getCard(jouables,autres)
          }
        } catch {case `PlayerTypeChangeException` => getCarteJoue}

        val carteJoue = getCarteJoue

        state = State.running
        Printer.joueurAJoue(carteJoue)

        // need to print 'belote' or 'rebelote'
        if (belote.exists(j => j.id == currentPlayer.id && j.equipe == enchereController.equipe) // player has belote and his team won the bidding
          && carteJoue.couleur == couleurAtout                                       // he plays a trump card
          && (carteJoue.valeur == Dame || carteJoue.valeur == Roi)) {                     // which is the queen or the king
          Printer.annonceBelote(currentPlayer.main.filter(_.couleur == couleurAtout).count(c => c.valeur == Dame || c.valeur == Roi) == 2)
        }

        currentPlayer.main = currentPlayer.main diff List(carteJoue)

        // Si c'est la premiere carte joue, renseigner la couleur demande
        if (couleurDemande.isEmpty) couleurDemande = Some(carteJoue.couleur)

        // On met a jour la carte maitre et le joueur correspondant
        if (carteMaitre.isEmpty) carteMaitre = Some(carteJoue)
        else if (carteJoue.stronger(couleurAtout,carteMaitre.get).getOrElse(false)) {carteMaitre = Some(carteJoue);joueurMaitre = currentPlayer}

        // Si c'est un atout, on regarde s'il est meilleur
        if ((carteJoue.couleur == couleurAtout) || (couleurDemande.exists(_ == carteJoue.couleur) && couleurAtout == Enchere.ToutAtout)) {
          //premier atout
          if (plusFortAtout.isEmpty) plusFortAtout = Some(carteJoue)
          else if (carteJoue.stronger(couleurAtout,plusFortAtout.get).getOrElse(false)) plusFortAtout = Some(carteJoue)
        }

        pli = (currentPlayer,carteJoue)::pli
        currentPlayer = nextPlayer(currentPlayer)
      }

      cartesJoueesWithPlayer = cartesJoueesWithPlayer ::: pli

      premierJoueur = vainqueur(pli.reverse,couleurAtout)

      // on regarde si capot/general chute
      if (premierJoueur.id != enchereController.current.get.id) {
        generalChute = true
        if (premierJoueur.idPartenaire != enchereController.current.get.id) capotChute = true
      }

      Printer.remporte(premierJoueur,pli.reverse)
      if (premierJoueur.equipe == NordSud) scoreNS = scoreNS + countPoints(couleurAtout,pli.unzip._2)
      tour = tour + 1
    }
    // dix de der
    if (premierJoueur.equipe == NordSud) scoreNS+=10

    // belote
    if (belote.exists(_.equipe == enchereController.equipe)) {
      if (enchereController.equipe == NordSud) scoreNS+=20 else scoreNS-=20
    }

    // etoiles
    if (enchereController.contrat < 250 && enchereController.coinche == Normal) {
      val preneur = partie.listJoueur.find(_.id == enchereController.id).get
      val part = partie.listJoueur.find(_.id == preneur.idPartenaire).get
      if (!capotChute && generalChute) partie.starMap += (preneur -> (partie.starMap(preneur)+1), part -> (partie.starMap(part)+1))
      if (!generalChute) partie.starMap += (premierJoueur -> (partie.starMap(premierJoueur)+2))
    }

    Printer.printScoreMain(scoreNS,enchereController.current.get,capotChute,generalChute)
    scoreNS
  }

  def countPoints(couleurAtout:Enchere.Couleur,plis:List[Card]):Int = couleurAtout match{
    case Enchere.SansAtout => plis.map(_.pointsSansAtout).sum
    case Enchere.ToutAtout => plis.map(_.pointsToutAtout).sum
    case a => plis.map({card => if (card.couleur == couleurAtout) card.pointsAtout
                                else card.pointsClassique}).sum
    }

  /**
   *
   * @param pli liste de couple (joueur,carte) du pli, dans l'ordre joue (1er carte joue en premiere position)
   * @param couleurAtout couleur de l'atout durant la main
   * @return Joueur ayant remporte le pli
   */
  def vainqueur(pli:List[(Joueur,Card)],couleurAtout:Enchere.Couleur):Joueur =
    pli.reduceLeft[(Joueur,Card)]({case ((j1,c1),(j2,c2)) =>
      if (c2.stronger(couleurAtout,c1).getOrElse(false)) (j2,c2) else (j1,c1)
    })._1

  /**
   * @param main Liste contenant les cartes du joueurs
   * @param couleurDemandeOption Couleur de la premiere carte joué durant ce pli (None si il n'y a pas encore de carte jouée)
   * @param couleurAtout Couleur de l'atout durant cette main
   * @param plusFortAtoutOption Plus fort atout sur la table (None s'il n'y a aucun atout sur la table)
   * @param joueurMaitre Joueur controllant le pli
   * @return Renvoie une paire de listes
   *         la premiere contient les cartes jouables,
   *         la deuxieme les cartes non-jouables
   */
  def cartesJouables(main:List[Card],
    couleurDemandeOption:Option[Enchere.Couleur],
    couleurAtout:Enchere.Couleur,
    plusFortAtoutOption:Option[Card],
    joueurMaitre:Joueur):(List[Card],List[Card]) =
      (couleurDemandeOption,plusFortAtoutOption) match {
        //premier a jouer, on fait ce qu'on veux
        case (None,_) => (main,List[Card]())

        // On joue a sans atout
        // le 'None' en meilleur carte sur la table est obligé
        // sinon, il y a une incoherence
        // todo match sur le cas ou ce n'est pas none et gerer l'exception
        case (Some(couleurDemande),_) if couleurAtout == 5 => {
          val cartesCouleurDemande = main.filter(_.couleur == couleurDemande)
          if (cartesCouleurDemande.isEmpty) // on a pas  la couleur demande
            (main,List[Card]())
          else (cartesCouleurDemande,main.diff(cartesCouleurDemande))
    }

    // On joue a tout atout
    // le 'Some' en meilleur carte sur la table est obligé
    // sinon, il y a une incoherence
    // todo match sur le cas ou ce n'est pas Some et gerer l'exception
        case (Some(couleurDemande),Some(plusForte)) if couleurAtout == 4 => {
          val cartesCouleurDemande = main.filter(_.couleur == couleurDemande)
          if (cartesCouleurDemande.isEmpty) // on a pas  la couleur demande
            (main,List[Card]())
          else { // On doit monter, ....
            val plusHautes = cartesCouleurDemande.filter(_.ordreAtout > plusForte.ordreAtout)
            // sauf si on ne peut pas
            if (plusHautes.isEmpty) (cartesCouleurDemande,main.diff(cartesCouleurDemande))
            else (plusHautes,main.diff(plusHautes))
        }
          }

          // On joue la couleur de l'atout
        case (Some(couleurDemande), Some(plusForte)) if couleurDemande == couleurAtout=> {
          val cartesCouleurDemande = main.filter(_.couleur == couleurDemande)
          if (cartesCouleurDemande.isEmpty) // on a pas  la couleur demande
            (main,List[Card]())
          else { // On doit monter, ....
            val plusHaute = cartesCouleurDemande.filter(_.ordreAtout > plusForte.ordreAtout)
            // sauf si on ne peut pas
            if (plusHaute.isEmpty) (cartesCouleurDemande,main.diff(cartesCouleurDemande))
            else (plusHaute,main.diff(plusHaute))
        }
          }

          // On joue une couleur autre que atout et un atout a ete joue
        case (Some(couleurDemande), Some(plusForte)) => {
          val cartesCouleurDemande = main.filter(_.couleur == couleurDemande)
          // on a pas  la couleur demande
          if (cartesCouleurDemande.isEmpty) {
            // si on a pas d'atout, on joue ce qu'on veut
            if (main.filter(_.couleur == couleurAtout).isEmpty) (main,List())
            // si on ne peut pas monter
          else if (main.exists(c => c.couleur == couleurAtout && c.ordreAtout < plusForte.ordreAtout)) {
            // si le part' est maitre, on joue ce qu'on veut
            if (joueurMaitre.id == currentPlayer.idPartenaire) (main,List())
            // sinon on doit sous-couper
          else main.partition(_.couleur == couleurAtout)
        }
        // si on peut monter
      else {
        // si le part' est maitre, on peut tout jouer sauf des atouts plus faibles
        if (joueurMaitre.id == currentPlayer.idPartenaire) {
          val plusFaibles = main.filter(c => c.couleur == couleurAtout && c.ordreAtout < plusForte.ordreAtout)
          (main diff plusFaibles,plusFaibles)
          }
          // sinon on doit sur-couper
          else main.partition(c => c.couleur == couleurAtout && c.ordreAtout > plusForte.ordreAtout)
        }
      }
      // on joue la carte qu'on veut dans la famille demande
    else {
      (cartesCouleurDemande,main.diff(cartesCouleurDemande))
          }
    }

    // On joue une couleur autre que atout
    // et pas encore d'atout joue
        case (Some(couleurDemande), None) => {
          val cartesCouleurDemande = main.filter(_.couleur == couleurDemande)
          // on a pas  la couleur demande
          if (cartesCouleurDemande.isEmpty) {
            // on doit couper, si on peut
            if (main.filter(_.couleur == couleurAtout).isEmpty || joueurMaitre.id == currentPlayer.idPartenaire) (main,List[Card]())
            else main.partition(_.couleur == couleurAtout)
        }
        // on joue la carte qu'on veut dans la famille demande
      else {
        (cartesCouleurDemande,main.diff(cartesCouleurDemande))
          }
      }
        case _ => println(couleurDemandeOption + " " + couleurAtout + " " + plusFortAtoutOption);(List[Card](),List[Card]())
        }


      }
