package fi.vm.sade.hakurekisteri.tools

import scala.util.Random

object OidTools {

  val min = 1000000000L
  val max = 10000000000L
  
  

  def genRandomLuhnCheckOID(root: String, node: String): String = {

    var oidBase = 0L;
	do {
	  oidBase = Random.nextLong % max
	} while(oidBase <= min)
    
    return root + "." + node + "." + oidBase + luhnCheck(oidBase);
  }
  
  def luhnCheck(oid: Long): String = {
    val oidString = oid.toString();
    var sum = 0;
    val yesNo = true;
      
    oidString.foreach(f => {
		var x = f.toInt;
		if(yesNo) x = 2 * x;
		if(x>9) x = (x % 10) +1;
		sum = sum + x;
    });
    
    val checkSum = sum % 10;
    return checkSum.toString;
    
  }
  
  def genRandomIBMCheckOID(root: String, node: String): String = {

    var oidBase = 0L;
	do {
	  oidBase = Random.nextLong % max
	} while(oidBase <= min)
    
    return root + "." + node + "." + oidBase + ibmCheck(oidBase);
  }
  
  def ibmCheck(oid: Long): String = {
    val oidString = oid.toString();
    var sum = 0;
    val alternative = List(7, 3, 1);
    var ai = alternative.iterator;
    
    def nextAlt: Int = {
      if(! ai.hasNext) ai = alternative.iterator;
      return ai.next;
    }

      
    oidString.foreach(f => sum += f.toInt * nextAlt);
    
    val checkSum = 10 - sum % 10;
    if (checkSum == 10) return "0"
    return checkSum.toString;
    
  }
}    