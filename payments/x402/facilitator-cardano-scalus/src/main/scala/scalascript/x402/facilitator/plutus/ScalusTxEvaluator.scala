package scalascript.x402.facilitator.plutus

import com.bloxbean.cardano.client.api.TransactionEvaluator
import com.bloxbean.cardano.client.api.model.Utxo
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag
import com.bloxbean.cardano.client.transaction.spec.Transaction

import java.util
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait ScalusTxEvaluator:
  def evaluate(tx: Transaction): Future[Seq[ScalusEvaluatedRedeemer]]

case class ScalusEvaluatedRedeemer(
  tag:     String,
  index:   Int,
  exUnits: ScalusExUnits,
)

object ScalusTxEvaluator:
  def bloxbean(
    evaluator: TransactionEvaluator,
    utxos:     util.Set[Utxo] = util.Collections.emptySet(),
  )(using ec: ExecutionContext): ScalusTxEvaluator =
    (tx: Transaction) => Future {
      val result = evaluator.evaluateTx(tx, utxos)
      if !result.isSuccessful then
        throw RuntimeException(s"Scalus Tx evaluation failed: ${result.getResponse}")
      Option(result.getValue).map(_.asScala.toSeq).getOrElse(Seq.empty).map { item =>
        val ex = item.getExUnits
        ScalusEvaluatedRedeemer(
          tag     = Option(item.getRedeemerTag).map(_.name()).getOrElse(""),
          index   = item.getIndex,
          exUnits = ScalusExUnits(BigInt(ex.getMem), BigInt(ex.getSteps)),
        )
      }
    }

  def claimSpendExUnits(results: Seq[ScalusEvaluatedRedeemer]): Option[ScalusExUnits] =
    results.find(r => r.tag == RedeemerTag.Spend.name() && r.index == 0).map(_.exUnits)
