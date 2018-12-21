package org.nervos.ckb.service;

import org.nervos.ckb.request.Request;
import org.nervos.ckb.response.*;
import org.nervos.ckb.response.item.Cell;

import java.util.Arrays;
import java.util.Collections;

/**
 * Created by duanyytop on 2018-12-20.
 * <p>
 * Copyright © 2018 Nervos Foundation. All rights reserved.
 */
public class JsonRpcNervosApiImpl implements NervosService {

    protected final APIService apiService;

    public JsonRpcNervosApiImpl(APIService apiService) {
        this.apiService = apiService;
    }

    @Override
    public Request<?, ResBlock> getBlock(String blockHash) {
        return new Request<>(
                "get_block",
                Arrays.asList(blockHash),
                apiService,
                ResBlock.class);
    }


    @Override
    public Request<?, ResTransaction> getTransaction(String transactionHash) {
        return new Request<>(
                "get_transaction",
                Arrays.asList(transactionHash),
                apiService,
                ResTransaction.class);
    }


    @Override
    public Request<?, ResBlockHash> getBlockHash(long blockNumber) {
        return new Request<>(
                "get_block_hash",
                Arrays.asList(blockNumber),
                apiService,
                ResBlockHash.class);
    }


    @Override
    public Request<?, ResHeader> getTipHeader() {
        return new Request<>(
                "get_tip_header",
                Collections.<String>emptyList(),
                apiService,
                ResHeader.class);
    }


    @Override
    public Request<?, ResCell> getCellsByTypeHash(String typeHash) {
        return new Request<>(
                "get_cells_by_type_hash",
                Arrays.asList(typeHash),
                apiService,
                ResCell.class);
    }


    @Override
    public Request<?, ResCell> getCurrentCell(Cell.OutPoint outPoint) {
        return new Request<>(
                "get_current_hash",
                Collections.<String>emptyList(),
                apiService,
                ResCell.class);
    }


    @Override
    public Request<?, ResTransactionHash> sendTransaction() {
        return new Request<>(
                "send_transaction",
                Collections.<String>emptyList(),
                apiService,
                ResTransactionHash.class);
    }


}
