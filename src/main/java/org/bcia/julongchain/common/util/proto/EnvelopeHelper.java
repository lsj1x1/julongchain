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
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Timestamp;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.bcia.julongchain.common.exception.JulongChainException;
import org.bcia.julongchain.common.exception.NodeException;
import org.bcia.julongchain.common.exception.ValidateException;
import org.bcia.julongchain.common.groupconfig.GroupConfigConstant;
import org.bcia.julongchain.common.groupconfig.MSPConfigHandler;
import org.bcia.julongchain.common.groupconfig.config.ApplicationConfig;
import org.bcia.julongchain.common.groupconfig.value.AnchorNodesValue;
import org.bcia.julongchain.common.localmsp.ILocalSigner;
import org.bcia.julongchain.common.log.JulongChainLog;
import org.bcia.julongchain.common.log.JulongChainLogFactory;
import org.bcia.julongchain.common.policies.PolicyConstant;
import org.bcia.julongchain.common.protos.ConfigUpdateEnvelopeVO;
import org.bcia.julongchain.common.protos.EnvelopeVO;
import org.bcia.julongchain.common.protos.ProposalVO;
import org.bcia.julongchain.common.resourceconfig.ResourcesConfigConstant;
import org.bcia.julongchain.common.util.CommConstant;
import org.bcia.julongchain.common.util.FileUtils;
import org.bcia.julongchain.common.util.ValidateUtils;
import org.bcia.julongchain.csp.factory.CspManager;
import org.bcia.julongchain.msp.ISigningIdentity;
import org.bcia.julongchain.msp.mgmt.GlobalMspManagement;
import org.bcia.julongchain.node.common.helper.ConfigTreeHelper;
import org.bcia.julongchain.node.common.helper.ConfigUpdateHelper;
import org.bcia.julongchain.protos.common.Common;
import org.bcia.julongchain.protos.common.Configtx;
import org.bcia.julongchain.protos.node.Configuration;
import org.bcia.julongchain.protos.node.ProposalPackage;
import org.bcia.julongchain.protos.node.ProposalResponsePackage;
import org.bcia.julongchain.protos.node.TransactionPackage;
import org.bcia.julongchain.tools.configtxgen.entity.GenesisConfig;
import org.bouncycastle.util.Arrays;

import java.io.IOException;

/**
 * 信封对象帮助类
 *
 * @author zhouhui
 * @date 2018/03/06
 * @company Dingxuan
 */
public class EnvelopeHelper {
    private static JulongChainLog log = JulongChainLogFactory.getLog(EnvelopeHelper.class);

    public static Common.Envelope makeGroupCreateTx(String groupId, ILocalSigner signer, Configtx.ConfigTree
            consenterSystemGroupTree, GenesisConfig.Profile profile) throws InvalidProtocolBufferException,
            ValidateException {
        Configtx.ConfigUpdate configUpdate = buildConfigUpdate(groupId, consenterSystemGroupTree, profile);

        Configtx.ConfigUpdateEnvelope.Builder envelopeBuilder = Configtx.ConfigUpdateEnvelope.newBuilder();
        envelopeBuilder.setConfigUpdate(configUpdate.toByteString());
        Configtx.ConfigUpdateEnvelope configUpdateEnvelope = envelopeBuilder.build();

        if (signer != null) {
            configUpdateEnvelope = signConfigUpdateEnvelope(configUpdateEnvelope, signer);
        }

        return buildSignedEnvelope(Common.HeaderType.CONFIG_UPDATE_VALUE, 0, groupId, signer, configUpdateEnvelope,
                0);
    }

    public static Configtx.ConfigEnvelope getConfigEnvelopeFrom(Common.Envelope envelope) throws
            InvalidProtocolBufferException, ValidateException {
        ValidateUtils.isNotNull(envelope, "Envelope can not be null");
        ValidateUtils.isNotNull(envelope.getPayload(), "Envelope.Payload can not be null");

        Common.Payload payload = Common.Payload.parseFrom(envelope.getPayload());

        ValidateUtils.isNotNull(payload.getHeader(), "Envelope.Payload.header can not be null");
        ValidateUtils.isNotNull(payload.getHeader().getGroupHeader(), "Envelope.groupHeader can not be null");
        ValidateUtils.isNotNull(payload.getData(), "Envelope.Payload.data can not be null");

        Common.GroupHeader groupHeader = Common.GroupHeader.parseFrom(payload.getHeader().getGroupHeader());

        if (groupHeader.getType() != Common.HeaderType.CONFIG_VALUE) {
            throw new ValidateException("Wrong groupHeader type");
        }

        return Configtx.ConfigEnvelope.parseFrom(payload.getData());
    }

    /**
     * 从信封对象中解析出ConfigUpdateEnvelope对象
     *
     * @param envelope
     * @return
     * @throws InvalidProtocolBufferException
     * @throws ValidateException
     */
    public static Configtx.ConfigUpdateEnvelope getConfigUpdateEnvelopeFrom(Common.Envelope envelope) throws
            InvalidProtocolBufferException, ValidateException {
        ValidateUtils.isNotNull(envelope, "Envelope can not be null");
        ValidateUtils.isNotNull(envelope.getPayload(), "Envelope.Payload can not be null");

        Common.Payload payload = Common.Payload.parseFrom(envelope.getPayload());

        ValidateUtils.isNotNull(payload.getHeader(), "Envelope.Payload.header can not be null");
        ValidateUtils.isNotNull(payload.getHeader().getGroupHeader(), "Envelope.groupHeader can not be null");
        ValidateUtils.isNotNull(payload.getData(), "Envelope.Payload.data can not be null");

        Common.GroupHeader groupHeader = Common.GroupHeader.parseFrom(payload.getHeader().getGroupHeader());

        if (groupHeader.getType() != Common.HeaderType.CONFIG_UPDATE_VALUE) {
            throw new ValidateException("Wrong groupHeader type");
        }

        return Configtx.ConfigUpdateEnvelope.parseFrom(payload.getData());
    }

    public static Configtx.ConfigUpdate buildConfigUpdate(String groupId, Configtx.ConfigTree
            consenterSystemGroupTree, GenesisConfig.Profile profile) throws InvalidProtocolBufferException,
            ValidateException {
        ValidateUtils.isNotNull(profile, "Profile can not be null");
        ValidateUtils.isNotNull(profile.getApplication(), "Profile.getApplication can not be null");
        ValidateUtils.isNotNull(profile.getConsortium(), "Profile.getConsortium can not be null");

        //构造应用子树
        Configtx.ConfigTree appTree = ConfigTreeHelper.buildApplicationTree(profile.getApplication());
        //得到最终的应用配置
        ApplicationConfig appConfig = new ApplicationConfig(appTree, new MSPConfigHandler(0));

        if (consenterSystemGroupTree != null) {
            //TODO:要实现吗？
        } else {

        }

        Configtx.ConfigTree originalTree = Configtx.ConfigTree.newBuilder().build();

        Configtx.ConfigTree.Builder groupTreeBuilder = Configtx.ConfigTree.newBuilder();
        groupTreeBuilder.putChilds(GroupConfigConstant.APPLICATION, appTree);
        Configtx.ConfigTree pendingTree = groupTreeBuilder.build();

//        Configtx.Config original = Configtx.Config.newBuilder().setGroupTree(originalTree).build();
        Configtx.Config pending = Configtx.Config.newBuilder().setGroupTree(pendingTree).build();

        Configtx.ConfigTree.Builder originalAppTreeBuilder = appTree.toBuilder().clearValues().clearPolicies();
        Configtx.Config.Builder originalBuilder = pending.toBuilder();

        originalBuilder.getGroupTreeBuilder().putChilds(GroupConfigConstant.APPLICATION,
                originalAppTreeBuilder.build());
        Configtx.Config original = originalBuilder.build();

        Configtx.ConfigUpdate configUpdate = ConfigUpdateHelper.compute(original, pending);

        Configtx.ConfigUpdate.Builder configUpdateBuilder = configUpdate.toBuilder();
        configUpdateBuilder.setGroupId(groupId);
        configUpdateBuilder.getReadSetBuilder().putValues(GroupConfigConstant.CONSORTIUM,
                Configtx.ConfigValue.newBuilder().setVersion(0).build());

        org.bcia.julongchain.protos.common.Configuration.Consortium consortium =
                org.bcia.julongchain.protos.common.Configuration.Consortium.newBuilder().setName(profile.getConsortium()).build();
        configUpdateBuilder.getWriteSetBuilder().putValues(GroupConfigConstant.CONSORTIUM,
                Configtx.ConfigValue.newBuilder().setVersion(0).setValue(consortium.toByteString()).build());

        if (appConfig.getCapabilities() != null && appConfig.getCapabilities().isResourcesTree()) {
            String defaultModPolicy = null;
            if (profile.getApplication().getResources() != null) {
                defaultModPolicy = profile.getApplication().getResources().getDefaultModPolicy();
            } else {
                defaultModPolicy = PolicyConstant.GROUP_APP_ADMINS;
            }

            configUpdateBuilder.putIsolatedData(ResourcesConfigConstant.RESOURCE_CONFIG_SEED_DATA,
                    makeResourcesConfig(defaultModPolicy).toByteString());
        }

        return configUpdateBuilder.build();
    }

    public static Configtx.Config makeResourcesConfig(String modPolicy) {
        Configtx.ConfigTree.Builder resourcesTreeBuilder = Configtx.ConfigTree.newBuilder();
        resourcesTreeBuilder.setModPolicy(modPolicy);
        resourcesTreeBuilder.putChilds(ResourcesConfigConstant.SMART_CONTRACTS,
                Configtx.ConfigTree.newBuilder().setModPolicy(modPolicy).build());
        resourcesTreeBuilder.putChilds(ResourcesConfigConstant.NODE_POLICIES,
                Configtx.ConfigTree.newBuilder().setModPolicy(modPolicy).build());
        resourcesTreeBuilder.putChilds(ResourcesConfigConstant.APIS,
                Configtx.ConfigTree.newBuilder().setModPolicy(modPolicy).build());
        Configtx.ConfigTree resourcesTree = resourcesTreeBuilder.build();

        Configtx.Config.Builder configBuilder = Configtx.Config.newBuilder();
        configBuilder.setType(Configtx.ConfigType.RESOURCE_VALUE);
        configBuilder.setGroupTree(resourcesTree);
        return configBuilder.build();
    }

    public static Configtx.ConfigUpdate makeConfigUpdate(String groupId, String orgName,
                                                         Configuration.AnchorNode[] anchorNodes) {
        Configtx.ConfigUpdate.Builder configUpdateBuilder = Configtx.ConfigUpdate.newBuilder();
        configUpdateBuilder.setGroupId(groupId);

        configUpdateBuilder.setReadSet(makeReadSet(orgName));
        configUpdateBuilder.setWriteSet(makeWriteSet(orgName, anchorNodes));

        return configUpdateBuilder.build();
    }

    private static Configtx.ConfigTree makeReadSet(String orgName) {
        Configtx.ConfigTree.Builder readSetBuilder = Configtx.ConfigTree.newBuilder();

        Configtx.ConfigTree.Builder orgTreeBuilder = Configtx.ConfigTree.newBuilder();
        orgTreeBuilder.putValues(GroupConfigConstant.MSP, Configtx.ConfigValue.getDefaultInstance());
        orgTreeBuilder.putPolicies(GroupConfigConstant.POLICY_READERS, Configtx.ConfigPolicy.getDefaultInstance());
        orgTreeBuilder.putPolicies(GroupConfigConstant.POLICY_WRITERS, Configtx.ConfigPolicy.getDefaultInstance());
        orgTreeBuilder.putPolicies(GroupConfigConstant.POLICY_ADMINS, Configtx.ConfigPolicy.getDefaultInstance());

        Configtx.ConfigTree.Builder appBuilder = Configtx.ConfigTree.newBuilder();
        appBuilder.putChilds(orgName, orgTreeBuilder.build());
        appBuilder.setVersion(1);
        appBuilder.setModPolicy(GroupConfigConstant.POLICY_ADMINS);

        readSetBuilder.putChilds(GroupConfigConstant.APPLICATION, appBuilder.build());
        return readSetBuilder.build();
    }

    private static Configtx.ConfigTree makeWriteSet(String orgName, Configuration.AnchorNode[] anchorNodes) {
        Configtx.ConfigTree.Builder writeSetBuilder = Configtx.ConfigTree.newBuilder();

        Configtx.ConfigTree.Builder orgTreeBuilder = Configtx.ConfigTree.newBuilder();
        orgTreeBuilder.putValues(GroupConfigConstant.MSP, Configtx.ConfigValue.getDefaultInstance());
        orgTreeBuilder.putPolicies(GroupConfigConstant.POLICY_READERS, Configtx.ConfigPolicy.getDefaultInstance());
        orgTreeBuilder.putPolicies(GroupConfigConstant.POLICY_WRITERS, Configtx.ConfigPolicy.getDefaultInstance());
        orgTreeBuilder.putPolicies(GroupConfigConstant.POLICY_ADMINS, Configtx.ConfigPolicy.getDefaultInstance());
        orgTreeBuilder.setVersion(1);
        orgTreeBuilder.setModPolicy(GroupConfigConstant.POLICY_ADMINS);

        Configtx.ConfigValue.Builder anchorNodesBuilder = Configtx.ConfigValue.newBuilder();
        anchorNodesBuilder.setModPolicy(GroupConfigConstant.POLICY_ADMINS);
        AnchorNodesValue anchorNodesValue = new AnchorNodesValue(anchorNodes);
        anchorNodesBuilder.setValue(anchorNodesValue.getValue().toByteString());
        orgTreeBuilder.putValues(GroupConfigConstant.ANCHOR_NODES, anchorNodesBuilder.build());

        Configtx.ConfigTree.Builder appBuilder = Configtx.ConfigTree.newBuilder();
        appBuilder.putChilds(orgName, orgTreeBuilder.build());
        appBuilder.setVersion(1);
        appBuilder.setModPolicy(GroupConfigConstant.POLICY_ADMINS);

        writeSetBuilder.putChilds(GroupConfigConstant.APPLICATION, appBuilder.build());
        return writeSetBuilder.build();
    }

    /**
     * @param originalProposal
     * @param identity
     * @param endorserResponses
     * @return
     * @throws ValidateException
     * @deprecated 方法体太长，准备拆分
     */
    public static Common.Envelope createSignedTxEnvelope(ProposalPackage.Proposal originalProposal, ISigningIdentity
            identity, ProposalResponsePackage.ProposalResponse... endorserResponses) throws ValidateException {
        if (originalProposal == null || identity == null || endorserResponses == null) {
            log.warn("Args should not be null");
            throw new ValidateException("Args should not be null");
        }

        //校验并获取Proposal头部
        Common.Header header = null;
        try {
            header = Common.Header.parseFrom(originalProposal.getHeader());
        } catch (InvalidProtocolBufferException e) {
            log.error(e.getMessage(), e);
            throw new ValidateException("Wrong proposal header");
        }

        //校验并获取Proposal负载，应为SmartContractProposalPayload对象
        ProposalPackage.SmartContractProposalPayload smartContractProposalPayload = null;
        try {
            smartContractProposalPayload = ProposalPackage.SmartContractProposalPayload.parseFrom(
                    originalProposal.getPayload());
        } catch (InvalidProtocolBufferException e) {
            log.error(e.getMessage(), e);
            throw new ValidateException("Wrong proposal payload");
        }

        //校验并获取签名头部
        Common.SignatureHeader signatureHeader = null;
        try {
            signatureHeader = Common.SignatureHeader.parseFrom(header.getSignatureHeader());
        } catch (InvalidProtocolBufferException e) {
            log.error(e.getMessage(), e);
            throw new ValidateException("Wrong proposal header signatureHeader");
        }

        //签名头部的消息创建者字段应与身份一致
        if (Arrays.compareUnsigned(signatureHeader.getCreator().toByteArray(), identity.getIdentity().serialize()) != 0) {
            throw new ValidateException("Wrong signatureHeader creator");
        }

        ProposalPackage.SmartContractHeaderExtension extension = null;
        try {
            Common.GroupHeader groupHeader = Common.GroupHeader.parseFrom(header.getGroupHeader());
            extension = ProposalPackage.SmartContractHeaderExtension.parseFrom(groupHeader.getExtension());
        } catch (InvalidProtocolBufferException e) {
            log.error(e.getMessage(), e);
            //不能成功转化，说明是错误的智能合约头部扩展
            throw new ValidateException("Wrong SmartContractHeaderExtension");
        }

        byte[] bytes = null;
        TransactionPackage.SmartContractEndorsedAction.Builder endorsedActionBuilder = TransactionPackage
                .SmartContractEndorsedAction.newBuilder();

        for (int i = 0; i < endorserResponses.length; i++) {
            ProposalResponsePackage.ProposalResponse endorserResponse = endorserResponses[i];

            if (endorserResponse.getResponse().getStatus() != 200 && endorserResponse.getResponse().getStatus() != 0) {
                throw new ValidateException("EndorserResponse status error: " + endorserResponse.getResponse());
            }

            if (i == 0) {
                bytes = endorserResponse.getResponse().getPayload().toByteArray();
                endorsedActionBuilder.setProposalResponsePayload(endorserResponse.getResponse().getPayload());
            } else {
                if (Arrays.compareUnsigned(bytes, endorserResponse.getResponse().getPayload().toByteArray()) != 0) {
                    throw new ValidateException("Should be same payload");
                }
            }

            endorsedActionBuilder.addEndorsements(endorserResponse.getEndorsement());
        }

        /**
         * 交易结构
         * Transaction
         *      \_ TransactionAction (1...n)
         *         |\_ Header (1)
         *          \_ SmartContractActionPayload (1)
         *             |\_ SmartContractProposalPayload (1)
         *              \_ SmartContractEndorsedAction (1)
         *                 |\_ Endorsement (1...n)
         *                  \_ ProposalResponsePayload
         *                      \_ SmartContractAction
         */
        //TODO:去掉transientMap属性？
        ProposalPackage.SmartContractProposalPayload.Builder clearProposalPayloadBuilder =
                smartContractProposalPayload.toBuilder();
        clearProposalPayloadBuilder.clearTransientMap();
        ProposalPackage.SmartContractProposalPayload clearProposalPayload = clearProposalPayloadBuilder.build();

        TransactionPackage.SmartContractActionPayload.Builder actionPayloadBuilder = TransactionPackage
                .SmartContractActionPayload.newBuilder();
        actionPayloadBuilder.setSmartContractProposalPayload(clearProposalPayload.toByteString());
        actionPayloadBuilder.setAction(endorsedActionBuilder);
        TransactionPackage.SmartContractActionPayload actionPayload = actionPayloadBuilder.build();

        TransactionPackage.TransactionAction.Builder transactionActionBuilder = TransactionPackage.TransactionAction
                .newBuilder();
        transactionActionBuilder.setHeader(header.getSignatureHeader());
        transactionActionBuilder.setPayload(actionPayload.toByteString());

        TransactionPackage.Transaction.Builder transactionBuilder = TransactionPackage.Transaction.newBuilder();
        transactionBuilder.addActions(transactionActionBuilder);
        TransactionPackage.Transaction transaction = transactionBuilder.build();

        Common.Payload.Builder payloadBuilder = Common.Payload.newBuilder();
        payloadBuilder.setHeader(header);
        payloadBuilder.setData(transaction.toByteString());
        Common.Payload payload = payloadBuilder.build();

        byte[] signature = identity.sign(payload.toByteArray());

        Common.Envelope.Builder envelopeBuilder = Common.Envelope.newBuilder();
        envelopeBuilder.setPayload(payload.toByteString());
        envelopeBuilder.setSignature(ByteString.copyFrom(signature));
        return envelopeBuilder.build();
    }

    /**
     * 构造交易信封
     *
     * @param originalProposal
     * @param identity
     * @param endorserResponses
     * @return
     * @throws ValidateException
     * @throws InvalidProtocolBufferException
     * @throws NodeException
     */
    public static Common.Envelope buildTxEnvelope(ProposalPackage.Proposal originalProposal, ISigningIdentity
            identity, ProposalResponsePackage.ProposalResponse... endorserResponses) throws ValidateException,
            InvalidProtocolBufferException, NodeException {
        ProposalVO proposalVO = new ProposalVO();
        proposalVO.parseFrom(originalProposal);

        //签名头部的消息创建者字段应与身份一致
        if (Arrays.compareUnsigned(proposalVO.getHeaderVO().getSignatureHeader().getCreator().toByteArray(), identity
                .getIdentity().serialize()) != 0) {
            throw new ValidateException("Wrong signatureHeader creator");
        }

        TransactionPackage.Transaction transaction = TransactionHelper.buildSingleTransaction(proposalVO.getPayloadVO
                ().toProto(), identity, endorserResponses);

        Common.Payload.Builder payloadBuilder = Common.Payload.newBuilder();
        Common.Header header = Common.Header.parseFrom(originalProposal.getHeader());
        payloadBuilder.setHeader(header);
        payloadBuilder.setData(transaction.toByteString());
        Common.Payload payload = payloadBuilder.build();

        return buildEnvelope(payload, identity);
    }

    /**
     * 从文件中读取成一个Envelope对象
     *
     * @param filePath
     * @return
     * @throws NodeException
     */
    public static Common.Envelope readFromFile(String filePath) throws NodeException {
        Common.Envelope envelope = null;
        try {
            byte[] bytes = FileUtils.readFileBytes(filePath);
            envelope = Common.Envelope.parseFrom(bytes);
            return envelope;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new NodeException("Can not read Group File");
        }
    }

    /**
     * 对原始Envelope对象进行完整性检查，并生成带签名的Envelope对象
     *
     * @param envelope
     * @param groupId
     * @param signer
     * @return
     * @deprecated 计划去掉ILocalSigner接口的使用，改用采用标准的ISigningIdentity接口
     */
    public static Common.Envelope sanityCheckAndSignConfigTx(Common.Envelope envelope, String groupId, ILocalSigner signer)
            throws NodeException {
        //检查Payload字段是否有误
        if (envelope.getPayload() == null || envelope.getPayload().isEmpty()) {
            //检查是否为空
            throw new NodeException("Missing payload");
        }
        Common.Payload payload = null;
        try {
            //从Envelope解析出Payload对象
            payload = Common.Payload.parseFrom(envelope.getPayload());
        } catch (InvalidProtocolBufferException e) {
            log.error(e.getMessage(), e);
            throw new NodeException("Wrong payload");
        }

        //检查Payload->Header->GroupHeader字段是否有误
        if (payload.getHeader() == null || payload.getHeader().getGroupHeader() == null) {
            throw new NodeException("Missing header");
        }
        Common.GroupHeader groupHeader = null;
        try {
            groupHeader = Common.GroupHeader.parseFrom(payload.getHeader().getGroupHeader());
        } catch (InvalidProtocolBufferException e) {
            log.error(e.getMessage(), e);
            throw new NodeException("Wrong header");
        }

        //检查消息类型
        if (groupHeader.getType() != Common.HeaderType.CONFIG_UPDATE_VALUE) {
            throw new NodeException("Wrong header type");
        }

        //检查群组ID
        if (StringUtils.isBlank(groupHeader.getGroupId())) {
            throw new NodeException("Missing group id");
        }
        if (!groupHeader.getGroupId().equals(groupId)) {
            throw new NodeException("Wrong group id");
        }

        //检查Data字段是否是ConfigUpdateEnvelope类型
        Configtx.ConfigUpdateEnvelope configUpdateEnvelope = null;

        try {
            configUpdateEnvelope = Configtx.ConfigUpdateEnvelope.parseFrom(payload.getData());
        } catch (InvalidProtocolBufferException e) {
            log.error(e.getMessage(), e);
            throw new NodeException("Wrong config update envelope");
        }

        Configtx.ConfigUpdateEnvelope signedConfigUpdateEnvelope = signConfigUpdateEnvelope(configUpdateEnvelope,
                signer);
        Common.Envelope signedEnvelope = buildSignedEnvelope(Common.HeaderType.CONFIG_UPDATE_VALUE, groupHeader.getVersion(),
                groupId, signer, signedConfigUpdateEnvelope, groupHeader.getEpoch());

        return signedEnvelope;
    }

    /**
     * 完整性检查，通过则签名该配置交易
     *
     * @param envelope
     * @param groupId
     * @return
     * @throws NodeException
     */
    public static Common.Envelope sanityCheckAndSignConfigTx(Common.Envelope envelope, String groupId) throws
            NodeException {
        //获取默认的身份（自身的身份）填充
        ISigningIdentity identity = GlobalMspManagement.getLocalMsp().getDefaultSigningIdentity();
        try {
            return sanityCheckAndSignConfigTx(envelope, groupId, identity);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new NodeException(e);
        }
    }

    /**
     * 完整性检查，通过则签名配置交易
     *
     * @param envelope
     * @param groupId
     * @param identity
     * @return
     * @throws NodeException
     * @throws InvalidProtocolBufferException
     * @throws ValidateException
     */
    public static Common.Envelope sanityCheckAndSignConfigTx(
            Common.Envelope envelope, String groupId, ISigningIdentity identity) throws NodeException,
            InvalidProtocolBufferException, ValidateException {
        EnvelopeVO envelopeVO = new EnvelopeVO();
        envelopeVO.parseFrom(envelope);

        //消息类型必须是CONFIG_UPDATE
        if (envelopeVO.getPayloadVO().getHeaderVO().getGroupHeaderVO().getType() != Common.HeaderType
                .CONFIG_UPDATE_VALUE) {
            String msg = "Wrong header type";
            throw new NodeException(msg);
        }

        //消息对象类型必须是ConfigUpdateEnvelope
        if (!(envelopeVO.getPayloadVO().getDataVO() instanceof ConfigUpdateEnvelopeVO)) {
            String msg = "Wrong msg type";
            throw new NodeException(msg);
        }

        //群组id必须匹配
        String groupIdInHeader = envelopeVO.getPayloadVO().getHeaderVO().getGroupHeaderVO().getGroupId();
        ValidateUtils.isNotBlank(groupIdInHeader, "Header's groupId can not be empty");
        if (!groupIdInHeader.equals(groupId)) {
            String msg = "Wrong group id";
            throw new NodeException(msg);
        }

        //获取Envelope对象的构造器
        Common.Envelope.Builder signEnvelopeBuilder = Common.Envelope.newBuilder(envelope);

        if (identity != null) {
            //Signature字段由Payload字段签名而成
            byte[] signatureBytes = identity.sign(envelope.getPayload().toByteArray());
            signEnvelopeBuilder.setSignature(ByteString.copyFrom(signatureBytes));
        } else {
            log.warn("Identity is null");
        }

        return signEnvelopeBuilder.build();
    }

    /**
     * 对一个ConfigUpdateEnvelope对象进行签名
     *
     * @param originalEnvelope
     * @param signer
     * @return
     * @deprecated 计划去掉ILocalSigner接口的使用，改用采用标准的ISigningIdentity接口
     */
    public static Configtx.ConfigUpdateEnvelope signConfigUpdateEnvelope(Configtx.ConfigUpdateEnvelope originalEnvelope,
                                                                         ILocalSigner signer) {
        //获取ConfigUpdateEnvelope对象的构造器,拷贝原对象
        Configtx.ConfigUpdateEnvelope.Builder envelopeBuilder = Configtx.ConfigUpdateEnvelope.newBuilder(originalEnvelope);

        //构造签名对象,由两个字段构成SignatureHeader和Signature（其中Signature是针对SignatureHeader+ConfigUpdate的签名）
        Configtx.ConfigSignature.Builder configSignatureBuilder = Configtx.ConfigSignature.newBuilder();
        Common.SignatureHeader signatureHeader = signer.newSignatureHeader();
        //由SignatureHeader+ConfigUpdate合成原始字节数组
        byte[] original = ArrayUtils.addAll(signatureHeader.toByteArray(), originalEnvelope.getConfigUpdate().toByteArray());
        //对原始数组进行签名
        byte[] signature = signer.sign(original);

        configSignatureBuilder.setSignatureHeader(signatureHeader.toByteString());
        configSignatureBuilder.setSignature(ByteString.copyFrom(signature));
        Configtx.ConfigSignature configSignature = configSignatureBuilder.build();

        //ConfigUpdateEnvelope对象由ConfigUpdate和若干个ConfigSignature组成。增加一个签名即可
        envelopeBuilder.addSignatures(configSignature);

        return envelopeBuilder.build();
    }

    /**
     * 为ConfigUpdateEnvelope签名一次（使用自己的身份）
     *
     * @param originalEnvelope
     * @return
     * @throws NodeException
     * @throws ValidateException
     */
    public static Configtx.ConfigUpdateEnvelope signConfigUpdateEnvelope(Configtx.ConfigUpdateEnvelope originalEnvelope)
            throws NodeException, ValidateException {
        //获取默认的身份（自身的身份）填充
        ISigningIdentity identity = GlobalMspManagement.getLocalMsp().getDefaultSigningIdentity();
        return signConfigUpdateEnvelope(originalEnvelope, identity);
    }

    /**
     * 为ConfigUpdateEnvelope签名一次
     *
     * @param originalEnvelope
     * @param identity
     * @return
     * @throws NodeException
     * @throws ValidateException
     */
    public static Configtx.ConfigUpdateEnvelope signConfigUpdateEnvelope(
            Configtx.ConfigUpdateEnvelope originalEnvelope, ISigningIdentity identity) throws NodeException,
            ValidateException {
        ValidateUtils.isNotNull(originalEnvelope, "ConfigUpdateEnvelope can not be null");
        ValidateUtils.isNotNull(originalEnvelope.getConfigUpdate(), "ConfigUpdateEnvelope can not be null");

        //获取ConfigUpdateEnvelope对象的构造器,拷贝原对象
        Configtx.ConfigUpdateEnvelope.Builder envelopeBuilder = Configtx.ConfigUpdateEnvelope.newBuilder
                (originalEnvelope);

        //构造签名对象,由两个字段构成SignatureHeader
        Common.SignatureHeader signatureHeader = buildSignatureHeader(identity);

        //由SignatureHeader+ConfigUpdate合成原始字节数组，再计算出签名
        byte[] signature = new byte[0];
        if (identity != null) {
            byte[] original = ArrayUtils.addAll(signatureHeader.toByteArray(), originalEnvelope.getConfigUpdate()
                    .toByteArray());
            //对原始数组进行签名
            signature = identity.sign(original);
        }

        Configtx.ConfigSignature.Builder configSignatureBuilder = Configtx.ConfigSignature.newBuilder();
        configSignatureBuilder.setSignatureHeader(signatureHeader.toByteString());
        configSignatureBuilder.setSignature(ByteString.copyFrom(signature));
        Configtx.ConfigSignature configSignature = configSignatureBuilder.build();

        //ConfigUpdateEnvelope对象由ConfigUpdate和若干个ConfigSignature组成。增加一个签名即可
        envelopeBuilder.addSignatures(configSignature);

        return envelopeBuilder.build();
    }

    /**
     * 构建带签名的信封对象
     *
     * @param type    消息类型
     * @param version 消息协议版本
     * @param groupId 群组ID
     * @param signer  签名者
     * @param data    数据对象
     * @param epoch   所属纪元
     * @return
     * @deprecated 计划去掉ILocalSigner接口的使用，改用采用标准的ISigningIdentity接口。见buildEnvelope
     */
    public static Common.Envelope buildSignedEnvelope(int type, int version, String groupId, ILocalSigner signer,
                                                      Message data, long epoch) {
        //获取Envelope对象的构造器
        Common.Envelope.Builder envelopeBuilder = Common.Envelope.newBuilder();

        //构造Payload
        Common.Payload payload = buildPayload(type, version, groupId, signer, data, epoch);
        envelopeBuilder.setPayload(payload.toByteString());

        if (signer != null) {
            //Signature字段由Payload字段签名而成
            byte[] signatureBytes = signer.sign(payload.toByteArray());
            envelopeBuilder.setSignature(ByteString.copyFrom(signatureBytes));
        }

        return envelopeBuilder.build();
    }

    /**
     * 构造Payload对象
     *
     * @param type    消息类型
     * @param version 消息协议版本
     * @param groupId 群组ID
     * @param signer  签名者
     * @param data    数据字段
     * @param epoch   所属纪元
     * @return
     * @throws NodeException
     * @deprecated 计划去掉ILocalSigner接口的使用，改用采用标准的ISigningIdentity接口
     */
    public static Common.Payload buildPayload(int type, int version, String groupId, ILocalSigner signer, Message
            data, long epoch) {
        //获取Payload对象的构造器
        Common.Payload.Builder payloadBuilder = Common.Payload.newBuilder();

        //构造头部,包含GroupHeader和SignatureHeader两个字段
        Common.GroupHeader groupHeader = buildGroupHeader(type, version, groupId, epoch);

        Common.Header.Builder headerBuilder = Common.Header.newBuilder();
        headerBuilder.setGroupHeader(groupHeader.toByteString());

        if (signer != null) {
            Common.SignatureHeader signatureHeader = signer.newSignatureHeader();
            headerBuilder.setSignatureHeader(signatureHeader.toByteString());
        }
        Common.Header header = headerBuilder.build();

        //Payload对象包含头部Header和Data两个字段
        payloadBuilder.setHeader(header);
        payloadBuilder.setData(data.toByteString());

        return payloadBuilder.build();
    }

    /**
     * 构造信封对象
     *
     * @param type      消息类型
     * @param version   消息协议的版本
     * @param groupId   群组ID
     * @param txId      交易ID
     * @param epoch     所属纪元，目前以所需区块的高度值填充
     * @param extension 智能合约扩展对象
     * @param data
     * @return
     * @throws NodeException
     */
    public static Common.Envelope buildEnvelope(
            int type, int version, String groupId, String txId, long epoch, ProposalPackage
            .SmartContractHeaderExtension extension, Message data) throws NodeException {
        //获取默认的身份（自身的身份）填充
        ISigningIdentity identity = GlobalMspManagement.getLocalMsp().getDefaultSigningIdentity();
        return buildEnvelope(type, version, groupId, txId, epoch, extension, identity, data);
    }

    /**
     * 构建信封对象
     *
     * @param type      消息类型
     * @param version   消息协议的版本
     * @param groupId   群组ID
     * @param txId      交易ID
     * @param epoch     所属纪元，目前以所需区块的高度值填充
     * @param extension 智能合约扩展对象
     * @param identity
     * @param data
     * @return
     * @throws NodeException
     */
    public static Common.Envelope buildEnvelope(
            int type, int version, String groupId, String txId, long epoch, ProposalPackage
            .SmartContractHeaderExtension extension, ISigningIdentity identity, Message data) throws NodeException {
        //构造Payload
        Common.Payload payload = buildPayload(type, version, groupId, txId, epoch, extension, identity, data);
        return buildEnvelope(payload, identity);
    }

    /**
     * 构造信封对象
     *
     * @param payload  信封负载，注意传入时确保不能为空
     * @param identity
     * @return
     * @throws NodeException
     */
    private static Common.Envelope buildEnvelope(Common.Payload payload, ISigningIdentity identity) throws
            NodeException {
        //获取Envelope对象的构造器
        Common.Envelope.Builder envelopeBuilder = Common.Envelope.newBuilder();

        //构造Payload
        envelopeBuilder.setPayload(payload.toByteString());

        if (identity != null) {
            //Signature字段由Payload字段签名而成
            byte[] signatureBytes = identity.sign(payload.toByteArray());
            envelopeBuilder.setSignature(ByteString.copyFrom(signatureBytes));
        } else {
            log.warn("Identity is null");
        }

        return envelopeBuilder.build();
    }

    /**
     * 构造交易负载
     *
     * @param type      消息类型
     * @param version   消息协议的版本
     * @param groupId   群组ID
     * @param txId      交易ID
     * @param epoch     所属纪元，目前以所需区块的高度值填充
     * @param extension 智能合约扩展对象
     * @param data
     * @return
     * @throws NodeException
     */
    public static Common.Payload buildPayload(
            int type, int version, String groupId, String txId, long epoch, ProposalPackage
            .SmartContractHeaderExtension extension, Message data) throws NodeException {
        //获取默认的身份（自身的身份）填充
        ISigningIdentity identity = GlobalMspManagement.getLocalMsp().getDefaultSigningIdentity();
        return buildPayload(type, version, groupId, txId, epoch, extension, identity, data);
    }

    /**
     * 构造交易负载
     *
     * @param type      消息类型
     * @param version   消息协议的版本
     * @param groupId   群组ID
     * @param txId      交易ID
     * @param epoch     所属纪元，目前以所需区块的高度值填充
     * @param extension 智能合约扩展对象
     * @param identity  构建交易对象的身份实体
     * @param data      不同的消息类型，可以是Transaction/ConfigEnvelope/ConfigUpdateEnvelope
     * @return
     * @throws NodeException
     */
    public static Common.Payload buildPayload(
            int type, int version, String groupId, String txId, long epoch, ProposalPackage
            .SmartContractHeaderExtension extension, ISigningIdentity identity, Message data) throws NodeException {
        //获取Payload对象的构造器
        Common.Payload.Builder payloadBuilder = Common.Payload.newBuilder();

        byte[] creator = null;
        if (identity != null) {
            creator = identity.getIdentity().serialize();
        } else {
            log.warn("Identity is null");
        }
        byte[] nonce = generateNonce();

        Common.Header header = buildHeader(type, version, groupId, txId, epoch, extension, creator, nonce);
        payloadBuilder.setHeader(header);

        if (data != null) {
            payloadBuilder.setData(data.toByteString());
        }

        return payloadBuilder.build();
    }

    /**
     * 获取随机数
     *
     * @return
     * @throws NodeException
     */
    public static byte[] generateNonce() throws NodeException {
        try {
            return CspManager.getDefaultCsp().rng(CommConstant.DEFAULT_NONCE_LENGTH, null);
        } catch (JulongChainException e) {
            log.error(e.getMessage(), e);
            throw new NodeException("Can not get nonce");
        }
    }

    /**
     * 生成交易Id
     *
     * @param creator
     * @param nonce
     * @return
     * @throws NodeException
     */
    public static String generateTxId(byte[] creator, byte[] nonce) throws NodeException {
        try {
            return ProposalUtils.computeProposalTxID(creator, nonce);
        } catch (JulongChainException e) {
            log.error(e.getMessage(), e);
            throw new NodeException("Generate txId fail");
        }
    }

    /**
     * 构造Header对象
     *
     * @param type      消息类型
     * @param version   消息协议的版本
     * @param groupId   群组ID
     * @param txId      交易ID
     * @param epoch     所属纪元，目前以所需区块的高度值填充
     * @param extension 智能合约扩展对象
     * @param creator   消息创建者
     * @param nonce     随机数，仅可使用一次。用于防止重播攻击
     * @return
     */
    public static Common.Header buildHeader(int type, int version, String groupId, String txId, long epoch,
                                            ProposalPackage.SmartContractHeaderExtension extension, byte[]
                                                    creator, byte[] nonce) {
        //构造GroupHeader对象
        Common.GroupHeader groupHeader = buildGroupHeader(type, version, groupId, txId, epoch, extension);
        //构造SignatureHeader对象
        Common.SignatureHeader signatureHeader = buildSignatureHeader(creator, nonce);

        //构造Header对象
        Common.Header.Builder headerBuilder = Common.Header.newBuilder();
        headerBuilder.setGroupHeader(groupHeader.toByteString());
        headerBuilder.setSignatureHeader(signatureHeader.toByteString());
        return headerBuilder.build();
    }

    /**
     * 构造GroupHeader对象
     *
     * @param type    消息类型
     * @param version 消息协议的版本
     * @param groupId 群组ID
     * @param epoch   所属纪元，目前以所需区块的高度值填充
     * @return
     */
    public static Common.GroupHeader buildGroupHeader(int type, int version, String groupId, long epoch) {
        return buildGroupHeader(type, version, nowTimestamp(), groupId, null, epoch, null);
    }

    /**
     * 构造GroupHeader对象
     *
     * @param type      消息类型
     * @param version   消息协议的版本
     * @param groupId   群组ID
     * @param txId      交易ID
     * @param epoch     所属纪元，目前以所需区块的高度值填充
     * @param extension 智能合约扩展对象
     * @return
     */
    public static Common.GroupHeader buildGroupHeader(int type, int version, String groupId, String txId, long epoch,
                                                      ProposalPackage.SmartContractHeaderExtension extension) {
        //默认填充当前的时间戳
        return buildGroupHeader(type, version, nowTimestamp(), groupId, txId, epoch, extension);
    }

    /**
     * 构造GroupHeader对象
     *
     * @param type
     * @param version
     * @param timestamp
     * @param groupId
     * @param txId
     * @param epoch
     * @param extension
     * @return
     */
    public static Common.GroupHeader buildGroupHeader(
            int type, int version, Timestamp timestamp, String groupId, String txId, long epoch, ProposalPackage
            .SmartContractHeaderExtension extension) {
        //首先构造GroupHeader对象
        Common.GroupHeader.Builder groupHeaderBuilder = Common.GroupHeader.newBuilder();
        groupHeaderBuilder.setType(type);
        groupHeaderBuilder.setVersion(version);
        if (timestamp != null) {
            groupHeaderBuilder.setTimestamp(timestamp);
        }
        if (StringUtils.isNotBlank(groupId)) {
            groupHeaderBuilder.setGroupId(groupId);
        }
        if (StringUtils.isNotBlank(txId)) {
            groupHeaderBuilder.setTxId(txId);
        }
        groupHeaderBuilder.setEpoch(epoch);

        if (extension != null) {
            groupHeaderBuilder.setExtension(extension.toByteString());
        }

        return groupHeaderBuilder.build();
    }

    /**
     * 基于身份信息构造SignatureHeader对象
     *
     * @param identity
     * @return
     * @throws NodeException
     */
    public static Common.SignatureHeader buildSignatureHeader(ISigningIdentity identity) throws NodeException {
        //构造签名对象,由两个字段构成SignatureHeader
        byte[] creator = null;
        if (identity != null) {
            creator = identity.getIdentity().serialize();
        } else {
            log.warn("Identity is null");
        }
        byte[] nonce = generateNonce();
        return buildSignatureHeader(creator, nonce);
    }

    /**
     * 构造SignatureHeader对象
     *
     * @param creator 消息创建者
     * @param nonce   随机数，仅可使用一次。用于防止重播攻击
     * @return
     */
    public static Common.SignatureHeader buildSignatureHeader(byte[] creator, byte[] nonce) {
        //构造SignatureHeader对象
        Common.SignatureHeader.Builder signatureHeaderBuilder = Common.SignatureHeader.newBuilder();
        if (creator != null) {
            signatureHeaderBuilder.setCreator(ByteString.copyFrom(creator));
        }
        if (nonce != null) {
            signatureHeaderBuilder.setNonce(ByteString.copyFrom(nonce));
        }
        return signatureHeaderBuilder.build();
    }

    /**
     * 获取当前的时间戳
     *
     * @return
     */
    public static Timestamp nowTimestamp() {
        long millis = System.currentTimeMillis();
        //完成秒和纳秒（即10亿分之一秒）的设置
        return Timestamp.newBuilder().setSeconds(millis / 1000)
                .setNanos((int) ((millis % 1000) * 1000000)).build();
    }
}