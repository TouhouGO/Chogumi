package io.github.touhougo.chogumi.core.model

/** 输入不合法的原因。UI 从这里映射资源，不复制判定。 */
enum class SearchInputError {
    OUT_OF_RANGE,
    EMPTY_TAG,
    EXCLUDED_PUBLIC_TAG,
    UNKNOWN_TYPE,
    UNKNOWN_DIMENSION_VALUE,
}

data class SearchValidationError(val reason: SearchInputError)

/** 校验结果：Valid 携带规范化后的值；Invalid 汇总全部错误，绝不静默夹取。 */
sealed interface SearchValidation<out T> {
    data class Valid<T>(val value: T) : SearchValidation<T>
    data class Invalid(val errors: List<SearchValidationError>) : SearchValidation<Nothing>
}
