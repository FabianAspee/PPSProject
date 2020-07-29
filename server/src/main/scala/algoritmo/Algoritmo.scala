package algoritmo

import java.sql.Date
import java.time.LocalDate
import java.util.concurrent.locks.ReentrantLock

import algoritmo.AssignmentOperation.InfoForAlgorithm
import caseclass.CaseClassDB._
import caseclass.CaseClassHttpMessage.{AlgorithmExecute, GruppoA, SettimanaN, SettimanaS}
import dbfactory.implicitOperation.ImplicitInstanceTableDB.{InstanceContratto, InstancePersona, InstanceRegola, InstanceRichiestaTeorica, InstanceTerminale}
import dbfactory.operation.TurnoOperation
import dbfactory.setting.Table.{PersonaTableQuery, StoricoContrattoTableQuery}
import messagecodes.StatusCodes
import slick.jdbc.SQLServerProfile.api._
import utils.DateConverter._
import utils.EmitterHelper

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
trait Algoritmo {

  /**
   * When this method is called, we call database for extract all information that the algorithm needs.
   * First of all we verify that information it's send, are be correct, verify that terminals that send existing in database,
   * that the group in list contains all two dates, that the normal week and special week contains a ruler that exist in
   * database and that the date that contains special week are be within time frame that run algorithm
   * @param algorithmExecute case class that contains all information for algorithm, this information is: init date,
   *                         finish date, id of terminals, list of group (Optional), normal week (Optional),
   *                         special week (Optional) and Three saturday ruler
   * @return Future of Option of Int that specified status of operation, this code can be :
   *         [[messagecodes.StatusCodes.SUCCES_CODE]] if all condition are satisfied
   *         [[messagecodes.StatusCodes.ERROR_CODE1]] if time frame have a problem, this can be:
   *                                                    time frame less that 28 days, dates to the contrary
   *         [[messagecodes.StatusCodes.ERROR_CODE2]] if list with terminal contains some terminal that not exist in database
   *         [[messagecodes.StatusCodes.ERROR_CODE3]] if group contains some error, this can be:
   *                                                  group with one date, date in group outside time frame, ruler in
   *                                                  group not exist in database
   *         [[messagecodes.StatusCodes.ERROR_CODE4]] if normal week contains some error, this can be:
   *                                                  idDay not correspond to day in week, ruler in week not exist in
   *                                                  database, shift in week not exist in database
   *         [[messagecodes.StatusCodes.ERROR_CODE5]] if special week contains some error, this can be:
   *                                                  idDay not correspond to day in week, ruler in week not exist in
   *                                                  database, shift in week not exist in database or date in week
   *                                                  is outside to time frame
   *         [[messagecodes.StatusCodes.ERROR_CODE6]] if time frame not contains theoretical request
   *         [[messagecodes.StatusCodes.ERROR_CODE7]] if some terminal not contains drivers availability for this time frame
   *         [[messagecodes.StatusCodes.ERROR_CODE8]] if not exist shift in database
   *         [[messagecodes.StatusCodes.ERROR_CODE9]] if a driver not contains a contract
   *         [[messagecodes.StatusCodes.ERROR_CODE10]] if the algorithm is already running
   *
   */
  def shiftAndFreeDayCalculus(algorithmExecute: AlgorithmExecute):Future[Option[Int]]

}
object Algoritmo extends Algoritmo{

  private val RUOLO_DRIVER=3
  private val MINIMUM_DAYS = 28
  private val future:Int=>Future[Option[Int]]=code=>Future.successful(Some(code))
  private val getRuler:List[Int]=>Future[Option[List[Regola]]]=idRegola=>InstanceRegola.operation().selectFilter(_.id.inSet(idRegola))
  private val getShift:Future[Option[List[Turno]]]=TurnoOperation.selectAll
  private val ENDED_DAY_OF_WEEK = 7
  private val FIRST_DAY_OF_WEEK = 1
  private val MINIMUM_DATE_FOR_GROUP=2
  private val verifyDateInt:Int=>Boolean=idDay => idDay<=ENDED_DAY_OF_WEEK && idDay>=FIRST_DAY_OF_WEEK
  private val verifyDate:(Date,Date,Date)=>Boolean=(day,initDay,finishDay) => {
    day.compareTo(initDay)>=0 && day.compareTo(finishDay)<=0 && getDayNumber(day)!=ENDED_DAY_OF_WEEK
  }

  private val lock = new ReentrantLock()
  private var running: Boolean = false

  override def shiftAndFreeDayCalculus(algorithmExecute: AlgorithmExecute): Future[Option[Int]] = {
    lock.lock()
    if(running){
      lock.unlock()
      future(StatusCodes.ERROR_CODE10)
    }
    else{
      running = true
      lock.unlock()
      verifyData(algorithmExecute).collect{
        case Some(StatusCodes.SUCCES_CODE) =>Some(StatusCodes.SUCCES_CODE)
        case value =>
          running = false
          value
      }
    }

  }

  private def verifyData(algorithmExecute: AlgorithmExecute): Future[Option[Int]] ={
    computeDaysBetweenDates(algorithmExecute.dateI,algorithmExecute.dateF)match {
      case value if value>=MINIMUM_DAYS =>getShift.flatMap {
        case Some(shift) => verifyTerminal(algorithmExecute,shift)
        case None =>future(StatusCodes.ERROR_CODE8)
      }
      case _ =>future(StatusCodes.ERROR_CODE1)
    }
  }

  private def verifyTerminal(algorithmExecute: AlgorithmExecute,shift:List[Turno]): Future[Option[Int]] = {
    algorithmExecute.idTerminal match {
      case Nil =>future(StatusCodes.ERROR_CODE2)
      case _ => InstanceTerminale.operation().selectFilter(_.id.inSet(algorithmExecute.idTerminal)).flatMap {
        case Some(value) if value.length==algorithmExecute.idTerminal.length=>verifyGroup(algorithmExecute,shift)
        case _ =>future(StatusCodes.ERROR_CODE2)
      }
    }
  }

  private def verifyGroup(algorithmExecute: AlgorithmExecute,shift:List[Turno]): Future[Option[Int]] = {
    @scala.annotation.tailrec
    def _verifyGroup(groups:List[GruppoA]):Future[Option[Int]] = groups match {
      case ::(head, next) if head.date.length>=MINIMUM_DATE_FOR_GROUP
        && head.date.forall(date=>verifyDate(date,algorithmExecute.dateI,algorithmExecute.dateF))=>_verifyGroup(next)
      case Nil =>verifyNormalWeek(algorithmExecute,shift)
      case _ =>future(StatusCodes.ERROR_CODE3)
    }
    algorithmExecute.gruppo match {
      case Some(groups) => getRuler(groups.map(_.regola)).flatMap {
        case Some(value) if value.length==groups.map(_.regola).distinct.length=>   _verifyGroup(groups)
        case _ =>future(StatusCodes.ERROR_CODE3)
      }
      case None =>verifyNormalWeek(algorithmExecute,shift)
    }
  }
  private val verifyRuler:List[Regola]=>List[Int]=>List[Int]=>List[Turno]=>Boolean=ruler=>weekWithRuler=>idShift=>shift=>{
    ruler.length==weekWithRuler.distinct.length && idShift.forall(turno=>shift.exists(idTurno=>idTurno.id.contains(turno)))
  }
  private def verifyNormalWeek(algorithmExecute: AlgorithmExecute,shift:List[Turno]): Future[Option[Int]] = {
    @scala.annotation.tailrec
    def _verifyNormalWeek(normalWeek:List[SettimanaN]):Future[Option[Int]] = normalWeek match {
      case ::(head, next) if verifyDateInt(head.idDay) =>_verifyNormalWeek(next)
      case Nil =>verifySpecialWeek(algorithmExecute,shift)
      case _ =>future(StatusCodes.ERROR_CODE4)
    }
    algorithmExecute.settimanaNormale match {
      case Some(normalWeek) => getRuler(normalWeek.map(_.regola)).flatMap {
        case Some(value) if verifyRuler(value)(normalWeek.map(_.regola))(normalWeek.map(_.turnoId))(shift)=>
                _verifyNormalWeek(normalWeek)
        case _ =>  future(StatusCodes.ERROR_CODE4)
      }
      case None =>verifySpecialWeek(algorithmExecute,shift)
    }
  }

  private def verifySpecialWeek(algorithmExecute: AlgorithmExecute,shift: List[Turno]): Future[Option[Int]] = {
    @scala.annotation.tailrec
    def _verifySpecialWeek(specialWeek:List[SettimanaS]):Future[Option[Int]] = specialWeek match {
      case ::(head, next) if verifyDateInt(head.idDay)
        && verifyDate(head.date,algorithmExecute.dateI,algorithmExecute.dateF) && getDayNumber(head.date)==head.idDay =>_verifySpecialWeek(next)
      case Nil =>verifyTheoricalRequest(algorithmExecute,shift)
      case _ =>future(StatusCodes.ERROR_CODE5)
    }
    algorithmExecute.settimanaSpeciale match {
      case Some(specialWeek) => getRuler(specialWeek.map(_.regola)).flatMap {
        case Some(value) if verifyRuler(value)(specialWeek.map(_.regola))(specialWeek.map(_.turnoId))(shift)=>
          _verifySpecialWeek(specialWeek)
        case _ =>  future(StatusCodes.ERROR_CODE5)
      }
      case None =>verifyTheoricalRequest(algorithmExecute,shift)
    }
  }

  private def verifyTheoricalRequest(algorithmExecute: AlgorithmExecute,shift: List[Turno]): Future[Option[Int]] = {
    InstanceRichiestaTeorica.operation().selectFilter(richiesta=>richiesta.dataInizio<=algorithmExecute.dateI
      && richiesta.dataFine>=algorithmExecute.dateF && richiesta.terminaleId.inSet(algorithmExecute.idTerminal)).flatMap {
        case Some(theoricalRequest) if theoricalRequest.map(_.terminaleId).equals(algorithmExecute.idTerminal)=>
          getPersonByTerminal(algorithmExecute,shift,theoricalRequest)
        case _ =>future(StatusCodes.ERROR_CODE6)
      }
  }

  private def getPersonByTerminal(algorithmExecute: AlgorithmExecute,shift: List[Turno],theoricalRequest:List[RichiestaTeorica]):Future[Option[Int]] ={
    val joinPersona = for{
      persona<-PersonaTableQuery.tableQuery()
      contratto<-StoricoContrattoTableQuery.tableQuery()
      if persona.id===contratto.personaId && persona.terminaleId.inSet(algorithmExecute.idTerminal) && contratto.dataInizio <= algorithmExecute.dateI
    }yield (contratto,persona)
    InstancePersona.operation().execJoin(joinPersona).flatMap {
      case Some(person) =>getAllContract(algorithmExecute,InfoForAlgorithm(shift,theoricalRequest,person))
      case None =>future(StatusCodes.ERROR_CODE7)
    }
  }
  private def getAllContract(algorithmExecute: AlgorithmExecute,infoForAlgorithm: InfoForAlgorithm):Future[Option[Int]]={
    InstanceContratto.operation().selectFilter(_.ruolo===RUOLO_DRIVER).collect {
      case Some(contract) =>  //TODO controllare i contratti
      val result = ExtractAlgorithmInformation().getAllData(algorithmExecute,infoForAlgorithm.copy(allContract=Some(contract)))
      AssignmentOperation.initOperationAssignment(algorithmExecute,result).foreach(_ =>{
        EmitterHelper.emitForAlgorithm(EmitterHelper.getFromKey("end-algorithm"))
        running = false
      })
        Some(StatusCodes.SUCCES_CODE)
      case None =>Some(StatusCodes.ERROR_CODE9)
    }
  }
}

object eas extends App{
  val timeFrameInit: Date =Date.valueOf(LocalDate.of(2020,6,1))
  val timeFrameFinish: Date =Date.valueOf(LocalDate.of(2020,6,30))
  val timeFrameInitError: Date =Date.valueOf(LocalDate.of(2020,6,1))
  val timeFrameFinishError: Date =Date.valueOf(LocalDate.of(2020,6,15))
  val terminals=List(1)//,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25
  val terminalWithoutTheoricRequest=List(4)
  val firstDateGroup: Date =Date.valueOf(LocalDate.of(2020,6,10))
  val secondDateGroup: Date =Date.valueOf(LocalDate.of(2020,6,11))
  val gruppi = List(GruppoA(1,List(firstDateGroup,secondDateGroup),2))
  val gruppiWithRulerNotExist = List(GruppoA(1,List(firstDateGroup,secondDateGroup),20),GruppoA(1,List(firstDateGroup),2))
  val normalWeek = List(SettimanaN(1,2,15,3),SettimanaN(2,2,15,2))
  val normalWeekWithIdDayGreater7 = List(SettimanaN(1,2,15,3),SettimanaN(9,2,15,2))
  val normalWeekWithShiftNotExist = List(SettimanaN(1,2,15,3),SettimanaN(2,50,15,2))
  val specialWeek = List(SettimanaS(1,2,15,3,Date.valueOf(LocalDate.of(2020,6,8))),SettimanaS(1,3,15,3,Date.valueOf(LocalDate.of(2020,6,8))))
  val specialWeekWithDateOutside  = List(SettimanaS(1,2,15,3,Date.valueOf(LocalDate.of(2020,6,8))),SettimanaS(1,3,15,3,Date.valueOf(LocalDate.of(2020,12,8))))
  val threeSaturday=false

  val algorithmExecute: AlgorithmExecute =
    AlgorithmExecute(timeFrameInit,timeFrameFinish,terminals,None,None,None,threeSaturday)
  Algoritmo.shiftAndFreeDayCalculus(algorithmExecute) onComplete(println)
  while(true){}
}

object esda extends App{
  val a = List(1,2,3,4)
  val b = List(1,2,3,4)
  val aa = List(1,1,2,2,3,3,4,4)
  val bb = List(1,2,3,4)
  println(a.equals(b))
  println(aa.equals(bb))
}