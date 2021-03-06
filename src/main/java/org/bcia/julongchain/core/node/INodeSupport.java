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
package org.bcia.julongchain.core.node;

import org.bcia.julongchain.common.groupconfig.config.IApplicationConfig;
import org.bcia.julongchain.common.resourceconfig.ISmartContractDefinition;

/**
 * 节点支持对象
 *
 * @author zhouhui
 * @date 2018/06/03
 * @company Dingxuan
 */
public interface INodeSupport {
    IApplicationConfig getApplicationConfig(String groupId);

    ISmartContractDefinition getSmartContractByName(String groupId, String scName);
}
