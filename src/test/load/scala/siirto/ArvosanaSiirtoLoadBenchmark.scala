object ArvosanaSiirtoLoadBenchmark extends PerformanceTest.Quickbenchmark {

  val lahde = "testitiedonsiirto"

  val kkGen = DataGen.int(1,12)
  def maxPaiva(kk:Int): Int = kk match {
    case 1 | 3 | 5 | 7 | 8 | 10 | 12 => 31
    case 2 => 28
    case _ => 30
  }
  val paivaGen = for (
    kk <- kkGen;
    pv <- DataGen.int(1,maxPaiva(kk))
  ) yield (pv, kk, 1999)


  val sukupuoliGen = DataGen.values("mies", "nainen")
  val merkit = "0123456789ABCDEFHJKLMNPRSTUVWXY"
  val valimerkit = "+-A"
  val hetuGen =  for (
    (pv, kk, vuosi) <- paivaGen;
    sukupuoli <- sukupuoliGen;
    luku <- DataGen.int(0,49)
  ) yield {
    val alku = "%02d".format(pv) + "%02d".format(kk) + "%04d".format(vuosi).substring(2)
    val finalLuku = sukupuoli match {
      case "mies" => luku * 2 + 1
      case _ => luku * 2
    }
    val loppu = "9" + "%02d".format(finalLuku)
    val merkki = merkit((alku + loppu).toInt % 31)
    val valimerkki = valimerkit((vuosi / 100) - 18)
    alku + valimerkki + loppu + merkki
  }

  val henkiloGen = for (
    hetu <- hetuGen
  ) yield <henkilo>
      <hetu>{hetu}</hetu>
      <lahtokoulu>05127</lahtokoulu>
      <luokka>9A</luokka>
      <sukunimi>Testinen</sukunimi>
      <etunimet>Juha Jaakko</etunimet>
      <kutsumanimi>Jaakko</kutsumanimi>
      <kotikunta>020</kotikunta>
      <aidinkieli>FI</aidinkieli>
      <kansalaisuus>246</kansalaisuus>
      <lahiosoite>Katu 1 A 1</lahiosoite>
      <postinumero>00100</postinumero>
      <matkapuhelin>040 1234 567</matkapuhelin>
      <muuPuhelin>09 1234 567</muuPuhelin>
      <perusopetus>
        <valmistuminen>2015-06-04</valmistuminen>
        <myontaja>05127</myontaja>
        <suorituskieli>FI</suorituskieli>
        <tila>KESKEN</tila>
        <yksilollistaminen>EI</yksilollistaminen>
      </perusopetus>
    </henkilo>

  def henkilotGen(size:Int) = DataGen.seq(henkiloGen, size)

  def batchDataGen(size:Int) = for (
    henkilot <- henkilotGen(size)
  ) yield <perustiedot>
      <eranTunniste>eranTunniste</eranTunniste>
      <henkilot>
        {henkilot}
      </henkilot>
    </perustiedot>

  def importBatchGen(size:Int) = for (
    data <- batchDataGen(size);
    id <- DataGen.uuid
  ) yield ImportBatch(data, Some("eranTunniste"), "perustiedot" , lahde, BatchState.READY, ImportStatus()).identify(id)

  val batchGen = Gen.range("batchSize")(0,200, 100).map((size) => new SerializableBatch(importBatchGen(size).generate))


  trait Registers extends Serializable {


    var dbHolder: Option[(ItPostgres, Database)] = None
    var systemHolder: Option[ActorSystem] = None
    var importBatchActorHolder: Option[ActorRef] = None
    var suoritusrekisteriHolder: Option[ActorRef] = None
    var opiskelijarekisteriHolder: Option[ActorRef] = None
    var henkiloActorHolder: Option[ActorRef] = None
    var organisaatioActorHolder: Option[ActorRef] = None



    def start(b: SerializableBatch) {
      if (systemHolder.isDefined) {
        system.shutdown()
      }
      if (itDbHolder.isDefined) {
        val (itDb, database) = db
        database.close()
        itDb.stop()
      }
      val portChooser = new ChooseFreePort
      dbHolder = Some((new ItPostgres(portChooser), Database.forUrl(s"jdbc:postgresql://localhost:${portChooser.chosenPort}/suoritusrekisteri")))
      systemHolder = Some(ActorSystem(s"perf-test-${UUID.randomUUID()}"))
      startActors(b: SerializableBatch)
    }

    def startActors(b: SerializableBatch):Unit

    def db = dbHolder.get
    def system = systemHolder.get
    def importBatchActor = importBatchActorHolder.get
    def suoritusrekisteri = suoritusrekisteriHolder.get
    def opiskelijarekisteri = opiskelijarekisteriHolder.get
    def henkiloActor = henkiloActorHolder.get
    def organisaatioActor = organisaatioActorHolder.get


  }

  val PerustiedotImportProps = (regs: Registers, b:ImportBatch with Identified[UUID]) =>  Props(new PerustiedotProcessingActor(regs.importBatchActor, regs.henkiloActor, regs.suoritusrekisteri, regs.opiskelijarekisteri, regs.organisaatioActor)(b))

  import akka.pattern.ask

  implicit val timeout: Timeout = 1.minute


  performance of "PerustiedotProcessing" in {

    measure method "process single batch" in {

      val registers = new Registers{
        override def startActors(b: SerializableBatch) {
          implicit val database = db._2
          importBatchActorHolder = Some(system.actorOf(Props[BatchActor]))
          val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuorituTable](TableQuery[SuoritusTable])
          suoritusrekisteriHolder = Some(system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 5))))
          val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
          opiskelijarekisteriHolder = Some(system.actorOf(Props(new OpiskelijaJDBCActor(opiskelijaJournal, 5))))
          henkiloActorHolder = Some(system.actorOf(Props[TestHenkiloActor]))
          organisaatioActorHolder = Some(system.actorOf(Props[TestOrganisaatioActor]))
        }
      }

      using(batchGen) setUp {
        b =>
          registers.start(b)
      } tearDown {
        _ =>
          registers.system.shutdown()
          registers.system.awaitTermination()
      } in {
        b =>
          processSingleBatch(registers, b)
      }


    }

    measure method "process single batch with henkilo actor and organisaatio actor" in {

      val registers = new Registers{
        override def startActors(b: SerializableBatch) {
          implicit val database = db._2
          importBatchActorHolder = Some(system.actorOf(Props[BatchActor]))
          val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
          suoritusrekisteriHolder = Some(system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal ,5))))
          val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
          opiskelijarekisteriHolder = Some(system.actorOf(Props(new OpiskelijaJDBCActor(opiskelijaJournal, 5))))

          val henkilot: Map[String, String] = (b.batch.data \\ "hetu").map((e: Node) => e.text).toSet[String].toList.zipWithIndex.toMap.mapValues(i => s"1.2.246.562.24.$i")

          def url(pattern:String) ="\\$".r.replaceAllIn(s"${pattern.replaceAll("\\?", "\\\\?")}", "(.*)").r


          val endPoint: Endpoint = new Endpoint {
            import org.json4s.jackson.JsonMethods._

            override def request(er: EndpointRequest): (Int, List[(String, String)], String) = er.body.map(parse(_) \ "hetu") match {
              case Some(JString(hetu)) if henkilot.contains(hetu) => (200, List(), henkilot(hetu))
              case _ => (404, List(), "Not Found")
            }



          }
          val asyncProvider =  new DelayingProvider(endPoint, 20.milliseconds)(system.dispatcher, system.scheduler)
          val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/oppijanumerorekisteri-service"), Some(new AsyncHttpClient(asyncProvider)))(system.dispatcher, system)


          henkiloActorHolder = Some(system.actorOf(Props(new HttpHenkiloActor(client, Config.mockConfig))))
          val orgs = (b.batch.data \\ "myontaja" ++ b.batch.data \\ "lahtokoulu").map(_.text).toSet.map((koodi: String) => koodi -> s"1.2.246.562.5.$koodi").toMap


          val orgEndPoint = new Endpoint {



            val all = url("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true")
            val single = url("http://localhost/organisaatio-service/rest/organisaatio/$")
            override def request(er: EndpointRequest): (Int, List[(String, String)], String) = er match {
              case EndpointRequest(all(), _, _ )  => (200, List(), "{\"numHits\":1,\"organisaatiot\":[{\"oid\":\"1.2.246.562.5.05127\",\"nimi\":{},\"oppilaitosKoodi\":\"05127\"}]}")
              case EndpointRequest(single(koodi),_,_) if orgs.contains(koodi) => (200, List(), "{\"oid\":\"" + orgs(koodi) + "\",\"nimi\":{},\"oppilaitosKoodi\":\"" + koodi + "\"}")
              case default => (404, List(), "Not Found")
            }
          }
          val orgProvider = new DelayingProvider(orgEndPoint, 20.milliseconds)(system.dispatcher, system.scheduler)
          val organisaatioClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/organisaatio-service"), Some(new AsyncHttpClient(orgProvider)))(system.dispatcher, system)
          organisaatioActorHolder = Some(system.actorOf(Props(new HttpOrganisaatioActor(organisaatioClient, Config.mockConfig))))

        }
      }

      using(batchGen) setUp {
        b =>
          registers.start(b)
      } tearDown {
        _ =>
          registers.system.shutdown()
          registers.system.awaitTermination()
      } in {
        b =>
          processSingleBatch(registers, b)
      }


    }


  }



  def processSingleBatch(registers: Registers, b: SerializableBatch): Any = {
    val doneFut = registers.importBatchActor ? AlertEnd(b.batch.externalId.get)
    registers.system.actorOf(PerustiedotImportProps(registers, b.batch))
    Await.result(doneFut, Duration.Inf)
  }

  case class AlertEnd(id: String)

  class BatchActor extends Actor {

    var alerts = Map[String, ActorRef]()
    override def receive: Actor.Receive = {
      case AlertEnd(id) =>  alerts = alerts + (id -> sender)
      case ImportBatch(_, Some(id), _ , _ , BatchState.DONE, _) if alerts.contains(id) => alerts(id) ! "done"
      case ImportBatch(_, Some(id), _ , _ , BatchState.FAILED, _) if alerts.contains(id) => alerts(id) ! Failure(new RuntimeException("batch failed"))
    }
  }

  class TestActor extends Actor {
    override def receive: Actor.Receive = Map()
  }

  class TestHenkiloActor extends Actor with ActorLogging {


    implicit val ec:ExecutionContext = context.dispatcher

    override def receive: Actor.Receive = {

      case SaveHenkilo(_, tunniste) =>
        log.debug(s"saving $tunniste")
        sender ! SavedHenkilo("1.2.3.4.5", tunniste)
    }
  }

  class TestOrganisaatioActor extends Actor with ActorLogging {
    override def receive: Actor.Receive = {
      case Oppilaitos(koodi) =>
        log.debug(s"fetching $koodi")

        sender ! OppilaitosResponse(koodi, Organisaatio("1.2.3", Map("fi" -> "testikoulu"), None, Some(koodi), None, Seq()))

    }
  }


  class SerializableBatch(var batch:ImportBatch with Identified[UUID]) extends Serializable {

    @throws(classOf[IOException])
    private def writeObject(out: ObjectOutputStream): Unit = {
      val xml = batch.data.toString
      val serialized =(xml, batch.externalId, batch.batchType, batch.source, batch.state, batch.status, batch.id)
      out.writeObject(serialized)
    }

    @throws(classOf[IOException])
    private def readObject(in: ObjectInputStream): Unit = {
      val obj: AnyRef = in.readObject()
      obj match {
        case (xml: String, externalId: Option[_], batchType:String ,source: String, state: BatchState, status: ImportStatus, id:UUID) =>
          batch = ImportBatch(SafeXML.loadString(xml), externalId.map(_.asInstanceOf[String]), batchType, source, state, status).identify(id)
        case _ => sys.error("wrong object type")
      }
    }

    override def toString: String = batch.toString
  }



}
