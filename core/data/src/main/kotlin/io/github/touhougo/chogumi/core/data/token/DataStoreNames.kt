package io.github.touhougo.chogumi.core.data.token

/** 凭证与会话的 DataStore 文件名，不进云备份；备份规则按这个名字排除。 */
const val TOKENS_DATASTORE_NAME = "chogumi_tokens"

/** 用户偏好的 DataStore 文件名，随云备份保留。 */
const val PREFERENCES_DATASTORE_NAME = "chogumi_preferences"
