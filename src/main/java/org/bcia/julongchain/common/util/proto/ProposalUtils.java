/**
 * Copyright Dingxuan. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.bcia.julongchain.common.util.proto;

import com.google.protobuf.ByteString;
import org.apache.commons.lang3.ArrayUtils;
import org.bcia.julongchain.common.exception.JulongChainException;
import org.bcia.julongchain.common.util.CommConstant;
import org.bcia.julongchain.csp.factory.CspManager;
import org.bcia.julongchain.csp.gm.dxct.RngOpts;
import org.bcia.julongchain.csp.intfs.ICsp;
import org.bcia.julongchain.msp.ISigningIdentity;
import org.bcia.julongchain.protos.common.Common;
import org.bcia.julongchain.protos.node.ProposalPackage;
import org.bcia.julongchain.protos.node.SmartContractPackage;
import org.bouncycastle.util.encoders.Hex;

import java.util.Map;

/**
 * Proposal工具类
 *
 * @author zhouhui
 * @date 2018/3/12
 * @company Dingxuan
 */
public class ProposalUtils {
    /**
     * 构造带签名的提案
     *
     * @param proposal
     * @param identity
     * @return
     */
    public static ProposalPackage.SignedProposal buildSignedProposal(ProposalPackage.Proposal proposal,
                                                                     ISigningIdentity identity) {
        //获取SignedProposal构造器
        ProposalPackage.SignedProposal.Builder signedProposalBuilder = ProposalPackage.SignedProposal.newBuilder();
        signedProposalBuilder.setProposalBytes(proposal.toByteString());

        //计算签名字段
        byte[] signatureBytes = identity.sign(proposal.toByteArray());
        signedProposalBuilder.setSignature(ByteString.copyFrom(signatureBytes));

        return signedProposalBuilder.build();
    }

    /**
     * 构造Proposal对象
     *
     * @param txId
     * @param type
     * @param groupId
     * @param scis
     * @param nonce
     * @param creator
     * @param transientMap
     * @return
     */
    public static ProposalPackage.Proposal buildSmartContractProposal(
            Common.HeaderType type, String groupId, String txId,
            SmartContractPackage.SmartContractInvocationSpec scis, byte[] nonce, byte[] creator, Map<String, byte[]>
                    transientMap) {
        //首先构造SmartContractHeaderExtension对象
        ProposalPackage.SmartContractHeaderExtension.Builder headerExtensionBuilder = ProposalPackage
                .SmartContractHeaderExtension.newBuilder();
        headerExtensionBuilder.setSmartContractId(scis.getSmartContractSpec().getSmartContractId());
        ProposalPackage.SmartContractHeaderExtension headerExtension = headerExtensionBuilder.build();

        //构造Header对象
        Common.Header header = EnvelopeHelper.buildHeader(type.getNumber(), 0, groupId, txId, 0,
                headerExtension, creator, nonce);

        //构造SmartContractProposalPayload对象
        ProposalPackage.SmartContractProposalPayload proposalPayload = buildProposalPayload(scis, transientMap);

        //构造Proposal对象
        ProposalPackage.Proposal.Builder proposalBuilder = ProposalPackage.Proposal.newBuilder();
        proposalBuilder.setHeader(header.toByteString());
        proposalBuilder.setPayload(proposalPayload.toByteString());

        return proposalBuilder.build();
    }

    /**
     * 构造Proposal的Payload字段
     *
     * @param scis
     * @param transientMap
     * @return
     */
    public static ProposalPackage.SmartContractProposalPayload buildProposalPayload(
            SmartContractPackage.SmartContractInvocationSpec scis, Map<String, byte[]> transientMap) {
        //SmartContractProposalPayload构造器
        ProposalPackage.SmartContractProposalPayload.Builder proposalPayloadBuilder = ProposalPackage
                .SmartContractProposalPayload.newBuilder();

        proposalPayloadBuilder.setInput(scis.toByteString());

        if (transientMap != null && !transientMap.isEmpty()) {
            for (String key : transientMap.keySet()) {
                proposalPayloadBuilder.putTransientMap(key, ByteString.copyFrom(transientMap.get(key)));
            }
        }

        return proposalPayloadBuilder.build();
    }

    /**
     * 生成交易ID
     *
     * @param creator
     * @param nonce
     * @return
     * @throws JulongChainException
     */
    public static String computeProposalTxID(byte[] creator, byte[] nonce) throws JulongChainException {
        byte[] bytes = ArrayUtils.addAll(nonce, creator);

        //哈希得到交易ID
        ICsp csp = CspManager.getDefaultCsp();
        byte[] resultBytes = csp.hash(bytes, null);

        //转换成十六进制字符串表示
        return Hex.toHexString(resultBytes);
    }

    /**
     * 根据SmartcontractInvocationSpec生成交易提案
     *
     * @param type
     * @param groupID
     * @param invocationSpec
     * @param creator
     * @return
     * @author sunianle
     */
    public static ProposalPackage.Proposal createProposalFromInvocationSpec(
            Common.HeaderType type, String groupID, SmartContractPackage.SmartContractInvocationSpec invocationSpec,
            byte[] creator) throws JulongChainException {
        return createSmartcontractProposal(type, groupID, invocationSpec, creator);
    }

    /**
     * @param type
     * @param groupID
     * @param invocationSpec
     * @param creator
     * @return
     * @throws JulongChainException
     * @author sunianle
     */
    private static ProposalPackage.Proposal createSmartcontractProposal(
            Common.HeaderType type, String groupID, SmartContractPackage.SmartContractInvocationSpec invocationSpec,
            byte[] creator) throws JulongChainException {
        return createSmartcontractProposalWithTransient(type, groupID, invocationSpec, creator, null);
    }

    /**
     * @param type
     * @param groupID
     * @param invocationSpec
     * @param creator
     * @param transientMap
     * @return
     * @throws JulongChainException
     * @author sunianle
     */
    public static ProposalPackage.Proposal createSmartcontractProposalWithTransient(
            Common.HeaderType type, String groupID, SmartContractPackage.SmartContractInvocationSpec invocationSpec,
            byte[] creator, Map<String, byte[]> transientMap) throws JulongChainException {
        byte[] nonce = CspManager.getDefaultCsp().rng(CommConstant.DEFAULT_NONCE_LENGTH, new RngOpts());
        String txID = ProposalUtils.computeProposalTxID(creator, nonce);
        return buildSmartContractProposal(type, groupID, txID, invocationSpec, nonce, creator, transientMap);
    }
}
