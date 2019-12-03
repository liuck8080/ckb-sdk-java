package org.nervos.ckb.transaction;

import java.io.IOException;
import java.math.BigInteger;
import java.util.*;
import org.nervos.ckb.service.Api;
import org.nervos.ckb.system.SystemContract;
import org.nervos.ckb.system.type.SystemScriptCell;
import org.nervos.ckb.type.Witness;
import org.nervos.ckb.type.cell.*;
import org.nervos.ckb.type.transaction.Transaction;
import org.nervos.ckb.utils.Calculator;
import org.nervos.ckb.utils.Numeric;
import org.nervos.ckb.utils.Serializer;
import org.nervos.ckb.utils.Strings;
import org.nervos.ckb.utils.address.AddressParseResult;
import org.nervos.ckb.utils.address.AddressParser;

/** Copyright © 2019 Nervos Foundation. All rights reserved. */
public class CellCollector {

  private Api api;
  private boolean skipDataAndType;

  public CellCollector(Api api, boolean skipDataAndType) {
    this.api = api;
    this.skipDataAndType = skipDataAndType;
  }

  public CellCollector(Api api) {
    this.api = api;
    this.skipDataAndType = true;
  }

  public CollectResult collectInputs(
      List<String> addresses,
      List<CellOutput> cellOutputs,
      BigInteger feeRate,
      int initialLength,
      List<CellDep> cellDeps,
      List<String> outputsData,
      List<String> headerDeps)
      throws IOException {

    List<String> lockHashes = new ArrayList<>();
    for (String address : addresses) {
      AddressParseResult addressParseResult = AddressParser.parse(address);
      lockHashes.add(addressParseResult.script.computeHash());
    }
    List<String> cellOutputsData = new ArrayList<>();
    for (int i = 0; i < cellOutputs.size() - 1; i++) {
      BigInteger size = cellOutputs.get(i).occupiedCapacity("0x");
      if (size.compareTo(Numeric.toBigInt(cellOutputs.get(i).capacity)) > 0) {
        throw new IOException("Cell output byte size must not be bigger than capacity");
      }
      cellOutputsData.add("0x");
    }
    SystemScriptCell systemScriptCell = SystemContract.getSystemSecpCell(api);
    cellOutputsData.add("0x");

    if (outputsData != null && outputsData.size() > 0) {
      cellOutputsData = outputsData;
    }

    List<CellDep> cellDepList =
        Collections.singletonList(new CellDep(systemScriptCell.outPoint, CellDep.DEP_GROUP));
    if (cellDeps != null && cellDeps.size() > 0) {
      cellDepList = cellDeps;
    }

    List<String> headerDepList = Collections.emptyList();
    if (headerDeps != null && headerDeps.size() > 0) {
      headerDepList = headerDeps;
    }

    Transaction transaction =
        new Transaction(
            "0",
            cellDepList,
            headerDepList,
            Collections.emptyList(),
            cellOutputs,
            cellOutputsData,
            Collections.emptyList());

    BigInteger inputsCapacity = BigInteger.ZERO;
    List<CellInput> cellInputs = new ArrayList<>();
    Map<String, List<CellInput>> lockInputsMap = new HashMap<>();
    for (String lockHash : lockHashes) {
      lockInputsMap.put(lockHash, new ArrayList<>());
    }
    List witnesses = new ArrayList<>();

    CellOutput changeOutput = cellOutputs.get(cellOutputs.size() - 1);

    BigInteger needCapacity = BigInteger.ZERO;
    for (CellOutput cellOutput : cellOutputs) {
      needCapacity = needCapacity.add(Numeric.toBigInt(cellOutput.capacity));
    }
    List<CellOutputWithOutPoint> cellOutputList;
    for (int index = 0; index < lockHashes.size(); index++) {
      long toBlockNumber = api.getTipBlockNumber().longValue();
      long fromBlockNumber = 1;

      while (fromBlockNumber <= toBlockNumber
          && inputsCapacity.compareTo(needCapacity.add(calculateTxFee(transaction, feeRate))) < 0) {
        long currentToBlockNumber = Math.min(fromBlockNumber + 100, toBlockNumber);
        cellOutputList =
            api.getCellsByLockHash(
                lockHashes.get(index),
                BigInteger.valueOf(fromBlockNumber).toString(),
                BigInteger.valueOf(currentToBlockNumber).toString());
        for (CellOutputWithOutPoint cellOutputWithOutPoint : cellOutputList) {
          if (skipDataAndType) {
            CellWithStatus cellWithStatus = api.getLiveCell(cellOutputWithOutPoint.outPoint, true);
            String outputsDataContent = cellWithStatus.cell.data.content;
            CellOutput cellOutput = cellWithStatus.cell.output;
            if ((!Strings.isEmpty(outputsDataContent) && !"0x".equals(outputsDataContent))
                || cellOutput.type != null) {
              continue;
            }
          }
          CellInput cellInput = new CellInput(cellOutputWithOutPoint.outPoint, "0x0");
          inputsCapacity = inputsCapacity.add(Numeric.toBigInt(cellOutputWithOutPoint.capacity));
          List<CellInput> cellInputList = lockInputsMap.get(lockHashes.get(index));
          cellInputList.add(cellInput);
          cellInputs.add(cellInput);
          witnesses.add("0x");
          transaction.inputs = cellInputs;
          transaction.witnesses = witnesses;
          BigInteger sumNeedCapacity =
              needCapacity
                  .add(calculateTxFee(transaction, feeRate))
                  .add(calculateOutputSize(changeOutput));
          if (inputsCapacity.compareTo(sumNeedCapacity) > 0) {
            // update witness of group first element
            int witnessIndex = 0;
            for (String lockHash : lockHashes) {
              if (lockInputsMap.get(lockHash).size() == 0) break;
              witnesses.set(witnessIndex, new Witness(NumberUtils.getZeros(initialLength)));
              witnessIndex += lockInputsMap.get(lockHash).size();
            }
            transaction.witnesses = witnesses;
            // calculate sum need capacity again
            sumNeedCapacity =
                needCapacity
                    .add(calculateTxFee(transaction, feeRate))
                    .add(calculateOutputSize(changeOutput));
            if (inputsCapacity.compareTo(sumNeedCapacity) > 0) {
              break;
            }
          }
        }
        fromBlockNumber = currentToBlockNumber + 1;
      }
    }
    if (inputsCapacity.compareTo(needCapacity.add(calculateTxFee(transaction, feeRate))) < 0) {
      throw new IOException("Capacity not enough!");
    }
    BigInteger changeCapacity =
        inputsCapacity.subtract(needCapacity.add(calculateTxFee(transaction, feeRate)));
    List<CellsWithAddress> cellsWithAddresses = new ArrayList<>();
    for (Map.Entry<String, List<CellInput>> entry : lockInputsMap.entrySet()) {
      cellsWithAddresses.add(
          new CellsWithAddress(
              entry.getValue(), addresses.get(lockHashes.indexOf(entry.getKey()))));
    }
    return new CollectResult(cellsWithAddresses, Numeric.toHexStringWithPrefix(changeCapacity));
  }

  public CollectResult collectInputs(
      String address, Transaction tx, BigInteger feeRate, int initialLength) throws IOException {

    AddressParseResult addressParseResult = AddressParser.parse(address);
    String lockHash = addressParseResult.script.computeHash();

    for (int i = 0; i < tx.outputs.size() - 1; i++) {
      BigInteger size = tx.outputs.get(i).occupiedCapacity("0x");
      if (size.compareTo(Numeric.toBigInt(tx.outputs.get(i).capacity)) > 0) {
        throw new IOException("Cell output byte size must not be bigger than capacity");
      }
    }

    Transaction transaction =
        new Transaction(
            "0",
            tx.cellDeps,
            tx.headerDeps,
            tx.inputs,
            tx.outputs,
            tx.outputsData,
            Collections.emptyList());

    BigInteger inputsCapacity = BigInteger.ZERO;
    for (CellInput cellInput : tx.inputs) {
      CellWithStatus cellWithStatus = api.getLiveCell(cellInput.previousOutput, false);
      inputsCapacity = inputsCapacity.add(Numeric.toBigInt(cellWithStatus.cell.output.capacity));
    }
    List witnesses = new ArrayList<>();
    witnesses.add(new Witness(NumberUtils.getZeros(initialLength)));

    CellOutput changeOutput = tx.outputs.get(tx.outputs.size() - 1);

    BigInteger needCapacity = BigInteger.ZERO;
    for (CellOutput cellOutput : tx.outputs) {
      needCapacity = needCapacity.add(Numeric.toBigInt(cellOutput.capacity));
    }
    List<CellOutputWithOutPoint> cellOutputList;
    long toBlockNumber = api.getTipBlockNumber().longValue();
    long fromBlockNumber = 1;

    while (fromBlockNumber <= toBlockNumber
        && inputsCapacity.compareTo(needCapacity.add(calculateTxFee(transaction, feeRate))) < 0) {
      long currentToBlockNumber = Math.min(fromBlockNumber + 100, toBlockNumber);
      cellOutputList =
          api.getCellsByLockHash(
              lockHash,
              BigInteger.valueOf(fromBlockNumber).toString(),
              BigInteger.valueOf(currentToBlockNumber).toString());
      for (CellOutputWithOutPoint cellOutputWithOutPoint : cellOutputList) {
        if (skipDataAndType) {
          CellWithStatus cellWithStatus = api.getLiveCell(cellOutputWithOutPoint.outPoint, true);
          String outputsDataContent = cellWithStatus.cell.data.content;
          CellOutput cellOutput = cellWithStatus.cell.output;
          if ((!Strings.isEmpty(outputsDataContent) && !"0x".equals(outputsDataContent))
              || cellOutput.type != null) {
            continue;
          }
        }
        CellInput cellInput = new CellInput(cellOutputWithOutPoint.outPoint, "0x0");
        inputsCapacity = inputsCapacity.add(Numeric.toBigInt(cellOutputWithOutPoint.capacity));
        witnesses.add("0x");
        transaction.inputs.add(cellInput);
        transaction.witnesses = witnesses;
        BigInteger sumNeedCapacity =
            needCapacity
                .add(calculateTxFee(transaction, feeRate))
                .add(calculateOutputSize(changeOutput));
        if (inputsCapacity.compareTo(sumNeedCapacity) > 0) {
          witnesses.set(1, new Witness());
          transaction.witnesses = witnesses;
          // calculate sum need capacity again
          sumNeedCapacity =
              needCapacity
                  .add(calculateTxFee(transaction, feeRate))
                  .add(calculateOutputSize(changeOutput));
          if (inputsCapacity.compareTo(sumNeedCapacity) > 0) {
            break;
          }
        }
      }
      fromBlockNumber = currentToBlockNumber + 1;
    }
    if (inputsCapacity.compareTo(needCapacity.add(calculateTxFee(transaction, feeRate))) < 0) {
      throw new IOException("Capacity not enough!");
    }
    BigInteger changeCapacity =
        inputsCapacity.subtract(needCapacity.add(calculateTxFee(transaction, feeRate)));
    List<CellsWithAddress> cellsWithAddresses = new ArrayList<>();
    cellsWithAddresses.add(new CellsWithAddress(transaction.inputs, address));
    return new CollectResult(cellsWithAddresses, Numeric.toHexStringWithPrefix(changeCapacity));
  }

  private BigInteger calculateTxFee(Transaction transaction, BigInteger feeRate) {
    int txSize = Serializer.serializeTransaction(transaction).toBytes().length;
    return Calculator.calculateTransactionFee(BigInteger.valueOf(txSize), feeRate);
  }

  public BigInteger getCapacityWithAddress(String address) throws IOException {
    AddressParseResult addressParseResult = AddressParser.parse(address);
    return getCapacityWithLockHash(addressParseResult.script.computeHash());
  }

  public BigInteger getCapacityWithLockHash(String lockHash) throws IOException {
    BigInteger capacity = BigInteger.ZERO;
    long toBlockNumber = api.getTipBlockNumber().longValue();
    long fromBlockNumber = 1;

    while (fromBlockNumber <= toBlockNumber) {
      long currentToBlockNumber = Math.min(fromBlockNumber + 100, toBlockNumber);
      List<CellOutputWithOutPoint> cellOutputs =
          api.getCellsByLockHash(
              lockHash,
              BigInteger.valueOf(fromBlockNumber).toString(),
              BigInteger.valueOf(currentToBlockNumber).toString());

      if (cellOutputs != null && cellOutputs.size() > 0) {
        for (CellOutputWithOutPoint output : cellOutputs) {
          if (skipDataAndType) {
            CellWithStatus cellWithStatus = api.getLiveCell(output.outPoint, true);
            String outputsDataContent = cellWithStatus.cell.data.content;
            CellOutput cellOutput = cellWithStatus.cell.output;
            if ((!Strings.isEmpty(outputsDataContent) && !"0x".equals(outputsDataContent))
                || cellOutput.type != null) {
              continue;
            }
          }
          capacity = capacity.add(Numeric.toBigInt(output.capacity));
        }
      }
      fromBlockNumber = currentToBlockNumber + 1;
    }
    return capacity;
  }

  private BigInteger calculateOutputSize(CellOutput cellOutput) {
    return BigInteger.valueOf(Serializer.serializeCellOutput(cellOutput).getLength());
  }
}
