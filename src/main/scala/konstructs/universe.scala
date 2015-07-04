package konstructs

import akka.actor.{ Actor, ActorRef, Props }
import konstructs.plugin.{ PluginConstructor, Config, ListConfig }
import konstructs.api._

class UniverseActor(name: String, jsonStorage: ActorRef, binaryStorage: ActorRef, chatFilters: Seq[ActorRef], blockListeners: Seq[ActorRef]) extends Actor {
  import UniverseActor._

  val generator = context.actorOf(GeneratorActor.props(jsonStorage, binaryStorage))
  val db = context.actorOf(DbActor.props(self, generator, binaryStorage))

  private var nextPid = 0

  def playerActorId(pid: Int) = s"player-$pid"

  def allPlayers(except: Option[Int] = None) = {
    val players = context.children.filter(_.path.name.startsWith("player-"))
    except match {
      case Some(pid) =>
        players.filter(_.path.name != playerActorId(pid))
      case None => players
    }
  }

  def player(nick: String, password: String) {
    val player = context.actorOf(PlayerActor.props(nextPid, nick, password, sender, db, self, jsonStorage, protocol.Position(0,32f,0,0,0)), playerActorId(nextPid))
    allPlayers(except = Some(nextPid)).foreach(_ ! PlayerActor.SendInfo(player))
    allPlayers(except = Some(nextPid)).foreach(player ! PlayerActor.SendInfo(_))
    nextPid = nextPid + 1
  }

  def receive = {
    case CreatePlayer(nick, password) =>
      player(nick, password)
    case m: PlayerActor.PlayerMovement =>
      allPlayers(except = Some(m.pid)).foreach(_ ! m)
    case l: PlayerActor.PlayerLogout =>
      allPlayers(except = Some(l.pid)).foreach(_ ! l)
    case b: BlockUpdate =>
      val chunk = ChunkPosition(b.pos)
      allPlayers() ++ blockListeners foreach { p =>
        p ! protocol.SendBlock(chunk.p, chunk.q, b.pos.x, b.pos.y, b.pos.z, b.newW)
      }
    case s: Say =>
      val filters = chatFilters :+ self
      filters.head.forward(SayFilter(filters.tail, s))
    case s: SayFilter =>
      allPlayers().foreach(_.forward(Said(s.message.text)))
    case s: Said =>
      allPlayers().foreach(_.forward(s))
    case p: PutBlock =>
      db.forward(p)
    case d: DestroyBlock =>
      db.forward(d)
  }
}

object UniverseActor {
  case class CreatePlayer(nick: String, password: String)

  @PluginConstructor
  def props(name: String, notUsed: ActorRef,
    @Config(key = "binary-storage") binaryStorage: ActorRef,
    @Config(key = "json-storage") jsonStorage: ActorRef,
    @ListConfig(key = "chat-filters", elementType = classOf[ActorRef]) chatFilters: Seq[ActorRef],
    @ListConfig(key = "block-listeners", elementType = classOf[ActorRef]) blockListeners: Seq[ActorRef]
  ): Props
  = Props(classOf[UniverseActor], name, jsonStorage, binaryStorage, chatFilters, blockListeners)

  @PluginConstructor
  def propsWithOnlyBlocks(name: String, notUsed: ActorRef,
    @Config(key = "binary-storage") binaryStorage: ActorRef,
    @Config(key = "json-storage") jsonStorage: ActorRef,
    @ListConfig(key = "block-listeners", elementType = classOf[ActorRef]) blockListeners: Seq[ActorRef]
  ): Props
  = Props(classOf[UniverseActor], name, jsonStorage, binaryStorage, Seq(), blockListeners)

  @PluginConstructor
  def props(name: String, notUsed: ActorRef,
    @Config(key = "binary-storage") binaryStorage: ActorRef,
    @Config(key = "json-storage") jsonStorage: ActorRef,
    @ListConfig(key = "chat-filters", elementType = classOf[ActorRef]) chatFilters: Seq[ActorRef]): Props
  = Props(classOf[UniverseActor], name, jsonStorage, binaryStorage, chatFilters, Seq())

  @PluginConstructor
  def props(name: String, notUsed: ActorRef,
    @Config(key = "binary-storage") binaryStorage: ActorRef,
    @Config(key = "json-storage") jsonStorage: ActorRef): Props
  = Props(classOf[UniverseActor], name, jsonStorage, binaryStorage, Seq(), Seq())
}
