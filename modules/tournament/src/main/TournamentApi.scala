package lila.tournament

import akka.actor.{ Props, ActorRef, ActorSelection, ActorSystem }
import akka.pattern.{ ask, pipe }
import chess.{ Mode, StartingPosition }
import org.joda.time.DateTime
import play.api.libs.json._
import scala.concurrent.duration._
import scalaz.NonEmptyList

import actorApi._
import lila.common.Debouncer
import lila.db.api._
import lila.game.{ Game, GameRepo }
import lila.hub.actorApi.lobby.{ ReloadTournaments, ReloadSimuls }
import lila.hub.actorApi.map.{ Tell, TellIds }
import lila.hub.actorApi.router.Tourney
import lila.hub.actorApi.timeline.{ Propagate, TourJoin }
import lila.hub.Sequencer
import lila.round.actorApi.round.{ ResignColor, GoBerserk, TournamentStanding }
import lila.socket.actorApi.SendToFlag
import lila.user.{ User, UserRepo }
import makeTimeout.short

private[tournament] final class TournamentApi(
    cached: Cached,
    system: ActorSystem,
    sequencers: ActorRef,
    autoPairing: AutoPairing,
    router: ActorSelection,
    renderer: ActorSelection,
    timeline: ActorSelection,
    socketHub: ActorRef,
    site: ActorSelection,
    lobby: ActorSelection,
    roundMap: ActorRef,
    trophyApi: lila.user.TrophyApi,
    roundSocketHub: ActorSelection) {

  def createTournament(setup: TournamentSetup, me: User): Fu[Tournament] = {
    var variant = chess.variant.Variant orDefault setup.variant
    val tour = Tournament.make(
      createdBy = me,
      clock = TournamentClock(setup.clockTime * 60, setup.clockIncrement),
      minutes = setup.minutes,
      waitMinutes = setup.waitMinutes,
      mode = setup.mode.fold(Mode.default)(Mode.orDefault),
      `private` = setup.`private`.isDefined,
      system = System.Arena,
      variant = variant,
      position = StartingPosition.byEco(setup.position).ifTrue(variant.standard) | StartingPosition.initial)
    TournamentRepo.insert(tour) >>- join(tour.id, me) inject tour
  }

  private[tournament] def createScheduled(schedule: Schedule): Funit =
    (Schedule durationFor schedule) ?? { minutes =>
      val created = Tournament.schedule(schedule, minutes)
      TournamentRepo.insert(created).void >>- publish()
    }

  def makePairings(oldTour: Tournament, pairings: NonEmptyList[Pairing], postEvents: Events) {
    Sequencing(oldTour.id)(TournamentRepo.startedById) { tour =>
      pairings.map { pairing =>
        PairingRepo.insert(pairing) >> autoPairing(tour, pairing)
      }.sequence map {
        _.list foreach { game =>
          sendTo(tour.id, StartGame(game))
        }
      }
    }
  }

  def start(oldTour: Tournament) {
    Sequencing(oldTour.id)(TournamentRepo.createdById) { tour =>
      TournamentRepo.setStatus(tour.id, Status.Started) >>-
        sendTo(tour.id, Reload) >>-
        publish()
    }
  }

  def wipe(tour: Tournament): Funit =
    TournamentRepo.remove(tour).void >>
      PairingRepo.removeByTour(tour.id) >>
      PlayerRepo.removeByTour(tour.id) >>- publish() >>- socketReload(tour.id)

  def finish(oldTour: Tournament) {
    Sequencing(oldTour.id)(TournamentRepo.startedById) { tour =>
      PairingRepo count tour.id flatMap {
        case 0 => wipe(tour)
        case _ => for {
          _ <- TournamentRepo.setStatus(tour.id, Status.Finished)
          _ <- PlayerRepo unWithdraw tour.id
          _ <- PairingRepo removePlaying tour.id
          winner <- PlayerRepo winner tour.id
          _ <- winner.??(p => TournamentRepo.setWinnerId(tour.id, p.userId))
        } yield {
          sendTo(tour.id, Reload)
          publish()
          PlayerRepo withPoints tour.id foreach {
            _ foreach { p => UserRepo.incToints(p.userId, p.score) }
          }
          // awardTrophies(tour)
        }
      }
    }
  }

  // private def awardTrophies(tour: Tournament): Funit =
  //   if (tour.schedule.??(_.freq == Schedule.Freq.Marathon)) tour.rankedPlayers.map {
  //     case rp if rp.rank == 1  => trophyApi.award(rp.player.id, _.MarathonWinner)
  //     case rp if rp.rank <= 10 => trophyApi.award(rp.player.id, _.MarathonTopTen)
  //     case rp if rp.rank <= 50 => trophyApi.award(rp.player.id, _.MarathonTopFifty)
  //     case _                   => funit
  //   }.sequenceFu.void
  //   else funit

  def join(tourId: String, me: User) {
    Sequencing(tourId)(TournamentRepo.enterableById) { tour =>
      PlayerRepo.join(tour.id, me, tour.perfLens) >> updateNbPlayers(tour.id) >>- {
        withdrawAllBut(tour.id, me.id)
        sendTo(tour.id, Joining(me.id))
        socketReload(tour.id)
        publish()
        timeline ! (Propagate(TourJoin(me.id, tour.id, tour.fullName)) toFollowersOf me.id)
      }
    }
  }

  private def updateNbPlayers(tourId: String) =
    PlayerRepo count tourId flatMap { TournamentRepo.setNbPlayers(tourId, _) }

  private def withdrawAllBut(tourId: String, userId: String) {
    TournamentRepo.allEnterable foreach {
      _ filter (_.id != tourId) foreach { other =>
        PlayerRepo.existsActive(other.id, userId) foreach {
          _ ?? withdraw(other.id, userId)
        }
      }
    }
  }

  def withdraw(tourId: String, userId: String) {
    Sequencing(tourId)(TournamentRepo.enterableById) {
      case tour if tour.isCreated =>
        PlayerRepo.remove(tour.id, userId) >> updateNbPlayers(tour.id) >>- socketReload(tour.id) >>- publish()
      case tour if tour.isStarted =>
        PlayerRepo.withdraw(tour.id, userId) >>- socketReload(tour.id) >>- publish()
      case _ => funit
    }
  }

  def berserk(gameId: String, userId: String) {
    GameRepo game gameId foreach {
      _.flatMap(_.tournamentId) foreach { tourId =>
        Sequencing(tourId)(TournamentRepo.startedById) { tour =>
          PairingRepo.findPlaying(tour.id, userId) flatMap {
            case Some(pairing) if pairing.berserkOf(userId) == 0 =>
              (pairing povRef userId) ?? { povRef =>
                GameRepo pov povRef flatMap {
                  _.filter(_.game.berserkable) ?? { pov =>
                    PairingRepo.setBerserk(pairing, userId, 1) >>- {
                      roundMap ! Tell(povRef.gameId, GoBerserk(povRef.color))
                    }
                  }
                }
              }
            case _ => funit
          }
        }
      }
    }
  }

  def finishGame(game: Game) {
    game.tournamentId foreach { tourId =>
      Sequencing(tourId)(TournamentRepo.startedById) { tour =>
        PairingRepo.finish(game) >>
          game.userIds.map(updatePlayer(tour)).sequenceFu.void >>-
          socketReload(tour.id) >>- updateTournamentStanding(tour)
      }
    }
  }

  def updatePlayer(tour: Tournament)(userId: String): Funit =
    (tour.perfType ?? { UserRepo.perfOf(userId, _) }) flatMap { perf =>
      PlayerRepo.update(tour.id, userId) { player =>
        tour.system.scoringSystem.sheet(tour, userId) map { sheet =>
          player.copy(
            score = sheet.total,
            fire = sheet.onFire,
            perf = perf.fold(player.perf)(_.intRating - player.rating)
          ).recomputeMagicScore
        }
      }
    }

  def ejectCheater(userId: String) {
    TournamentRepo.allEnterable foreach {
      _ foreach { tour =>
        PlayerRepo.existsActive(tour.id, userId) foreach {
          _ ?? ejectCheater(tour.id, userId)
        }
      }
    }
  }

  def ejectCheater(tourId: String, userId: String) {
    Sequencing(tourId)(TournamentRepo.enterableById) { tour =>
      PlayerRepo.remove(tour.id, userId) >>- socketReload(tour.id) >>- publish()
    }
  }

  private val miniStandingCache = lila.memo.AsyncCache[String, List[RankedPlayer]](
    (id: String) => PlayerRepo.bestByTourWithRank(id, 30),
    timeToLive = 3 second)

  def miniStanding(tourId: String, withStanding: Boolean): Fu[Option[MiniStanding]] =
    TournamentRepo byId tourId flatMap {
      _ ?? { tour =>
        if (withStanding) miniStandingCache(tour.id) map { rps =>
          MiniStanding(tour, rps.some).some
        }
        else fuccess(MiniStanding(tour, none).some)
      }
    }

  def miniStanding(tourId: String, userId: Option[String], withStanding: Boolean): Fu[Option[MiniStanding]] =
    userId ?? { uid =>
      PlayerRepo.exists(tourId, uid) flatMap {
        _ ?? miniStanding(tourId, withStanding)
      }
    }

  def fetchVisibleTournaments: Fu[VisibleTournaments] =
    cached.allCreatedSorted(true) zip
      TournamentRepo.publicStarted zip
      TournamentRepo.finished(10) map {
        case ((created, started), finished) =>
          VisibleTournaments(created, started, finished)
      }

  private def sequence(tourId: String)(work: => Funit) {
    sequencers ! Tell(tourId, Sequencer work work)
  }

  private def Sequencing[T <: Tournament](tourId: String)(fetch: String => Fu[Option[T]])(run: T => Funit) {
    sequence(tourId) {
      fetch(tourId) flatMap {
        case Some(t) => run(t)
        case None    => fufail(s"Can't run sequenced operation on missing tournament $tourId")
      }
    }
  }

  private def socketReload(tourId: String) {
    sendTo(tourId, Reload)
  }

  private object publish {
    private val siteMessage = SendToFlag("tournament", Json.obj("t" -> "reload"))
    private val debouncer = system.actorOf(Props(new Debouncer(5 seconds, {
      (_: Debouncer.Nothing) =>
        site ! siteMessage
        TournamentRepo.promotable foreach { tours =>
          renderer ? TournamentTable(tours) map {
            case view: play.twirl.api.Html => ReloadTournaments(view.body)
          } pipeToSelection lobby
        }
    })))
    def apply() { debouncer ! Debouncer.Nothing }
  }

  private object updateTournamentStanding {
    private val debouncer = system.actorOf(Props(new Debouncer(10 seconds, {
      (tourId: String) =>
        PairingRepo playingGameIds tourId foreach { ids =>
          roundSocketHub ! TellIds(ids, TournamentStanding(tourId))
        }
    })))
    def apply(tour: Tournament) {
      debouncer ! tour.id
    }
  }

  private def sendTo(tourId: String, msg: Any) {
    socketHub ! Tell(tourId, msg)
  }
}
