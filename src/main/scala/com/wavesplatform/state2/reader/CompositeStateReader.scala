package com.wavesplatform.state2.reader

import cats.implicits._
import com.wavesplatform.state2._
import scorex.account.{Account, Alias}
import scorex.transaction.Transaction
import scorex.transaction.assets.exchange.{ExchangeTransaction, Order}

class CompositeStateReader(inner: StateReader, blockDiff: BlockDiff) extends StateReader {
  private val txDiff = blockDiff.txsDiff

  override def transactionInfo(id: ByteArray): Option[(Int, Transaction)] =
    txDiff.transactions.get(id)
      .map(t => (t._1, t._2))
      .orElse(inner.transactionInfo(id))

  override def accountPortfolio(a: Account): Portfolio =
    inner.accountPortfolio(a).combine(txDiff.portfolios.get(a).orEmpty)

  override def assetInfo(id: ByteArray): Option[AssetInfo] = (inner.assetInfo(id), txDiff.issuedAssets.get(id)) match {
    case (None, None) => None
    case (existing, upd) => Some(existing.orEmpty.combine(upd.orEmpty))
  }

  override def height: Int = inner.height + blockDiff.heightDiff

  override def nonEmptyAccounts: Seq[Account] =
    inner.nonEmptyAccounts ++ txDiff.portfolios.keySet

  override def accountTransactionIds(a: Account): Seq[ByteArray] = {
    val fromDiff = txDiff.accountTransactionIds.get(a).orEmpty
    fromDiff ++ inner.accountTransactionIds(a)
  }

  override def effectiveBalanceAtHeightWithConfirmations(acc: Account, height: Int, confs: Int): Long = {
    val localEffectiveBalanceSnapshotsOfAccount = blockDiff.effectiveBalanceSnapshots
      .filter(ebs => ebs.acc == acc)

    lazy val relatedUpdates = localEffectiveBalanceSnapshotsOfAccount.filter(_.height > height - confs)
    lazy val storedEffectiveBalance = inner.effectiveBalanceAtHeightWithConfirmations(acc, height - blockDiff.heightDiff, confs - blockDiff.heightDiff)

    if (localEffectiveBalanceSnapshotsOfAccount.isEmpty)
      storedEffectiveBalance
    else {
      if (confs < blockDiff.heightDiff) {
        relatedUpdates.headOption match {
          case None => localEffectiveBalanceSnapshotsOfAccount.last.effectiveBalance
          case Some(relatedUpdate) => Math.min(relatedUpdate.prevEffectiveBalance, relatedUpdates.map(_.effectiveBalance).min)
        }
      }
      else {
        val localMin = localEffectiveBalanceSnapshotsOfAccount.map(_.effectiveBalance).min
        val prevEffBalance = if (inner.height == 0)
          localEffectiveBalanceSnapshotsOfAccount.head.prevEffectiveBalance
        else
          storedEffectiveBalance
        Math.min(prevEffBalance, localMin)

      }
    }
  }

  override def paymentTransactionIdByHash(hash: ByteArray): Option[ByteArray]
  = blockDiff.txsDiff.paymentTransactionIdsByHashes.get(hash)
    .orElse(inner.paymentTransactionIdByHash(hash))

  override def maxPaymentTransactionTimestampInPreviousBlocks(a: Account): Option[Long] = {
    blockDiff.maxPaymentTransactionTimestamp.get(a)
      .orElse(inner.maxPaymentTransactionTimestampInPreviousBlocks(a))
  }

  override def aliasesOfAddress(a: Account): Seq[Alias] =
    txDiff.aliases.filter(_._2 == a).keys.toSeq ++ inner.aliasesOfAddress(a)

  override def resolveAlias(a: Alias): Option[Account] = txDiff.aliases.get(a).orElse(inner.resolveAlias(a))

  override def findPreviousExchangeTxs(orderId: EqByteArray): Set[ExchangeTransaction] = {
    val newEtxs = txDiff.transactions
      .collect { case (_, (_, ets: ExchangeTransaction, _)) => ets }
      .filter(etx => (etx.buyOrder.id sameElements orderId.arr) || (etx.sellOrder.id sameElements orderId.arr))
      .toSet
    newEtxs ++ inner.findPreviousExchangeTxs(orderId)
  }
}
