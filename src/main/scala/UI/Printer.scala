package UI

import GameLogic.{Joueur, Enchere, Card}

trait Printer {

  /**
   * Affiche les cartes du joueur courant, trier par famille.
   * Utiliser uniquement durant les encheres (et en mode console).
   */
  def printCartes()

  /**
   * Affiche toutes les encheres effectuees durant ce tour d'annonces
   */
  def printListEnchere()

  /**
   * Show everyone their hand.
   */
  def printCardsToAll()

  /*
  /**
   * Affiche les differentes commandes accessibles, ainsi que leur but
   * Peut-etre inutile avec une vrai interface graphique
   */
  def printHelp() {}
  */

  /**
   * Affiche les scores (ou les met a jour pour une GUI).
   * Appelée a chaque fin de main.
   */
  def printScores()

  /**
   * Affiche le nombre de points fait par chaque equipe, si la donne est chutee, etc
   *
   * @param scoreNS Nombre de points fait par Nord/Sud durant cette main
   * @param enchere Enchere de la main
   */
  def printScoreMain(scoreNS:Int,enchere:Enchere)

  /**
   * Affiche les cartes de la main du joueur.
   * Appelée a chaque tour du joueur.
   * @param jouables cartes jouables en fonction des cartes deja jouees
   * @param autres cartes non jouables
   */
  def printCartes(jouables:List[Card],autres:List[Card])

  /**
   * Affiche "A X de parler"
   * @param joueur
   */
  def tourJoueurEnchere(joueur:Joueur)

  /**
   * Affiche "A X de jouer"
   * @param j
   */
  def tourJoueur(j:Joueur)

  /*
  /**
   * Affiche l'enchere courante.
   * Peut-etre inutile avec une vrai interface graphique (si l'enchere est toujours affichée).
   */
  def printEnchere() {}
  */

  /**
   * Affiche la carte joue.
   * @param c la carte joue
   */
  def joueurAJoue(c:Card)

  /**
   * Signale quel joueur a remporte le pli, et comment.
   * @param joueur le joueur ayant remporte le pli
   * @param plis List de type (Joueur,Card)
   *             Represente les cartes jouées, dans l'ordre (FIFO).
   */
  def remporte(joueur:Joueur,plis:List[(Joueur,Card)])

  /**
   * Affiche qui a gagner, les scores
   * @param NS score Nord/Sud
   * @param EO score Est/Ouest
   */
  def printFin(NS:Int,EO:Int)

  /**
   * Affiche "pas de prise"
   */
  def pasDePrise()

  /**
   * Annonce la fin des encheres, et affiche l'enchere gagnante
   * @param e enchere gagnante.
   */
  def enchereFinie(e:Enchere)

}
