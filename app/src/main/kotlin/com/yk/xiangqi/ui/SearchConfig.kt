package com.yk.xiangqi.ui

import com.yk.xiangqi.engine.GoParams

/** 用户为一方保存的一项搜索配置；由界面层投影为 UCI GoParams。 */
data class SearchConfig(
    val kind: Kind,
    val value: Long,
) {
    enum class Kind {
        MOVETIME, DEPTH, NODES,
    }

    fun toGoParams(searchMoves: List<String>? = null): GoParams = GoParams().also { params ->
        when (kind) {
            Kind.MOVETIME -> params.moveTime = value
            Kind.DEPTH -> params.depth = value.toInt()
            Kind.NODES -> params.nodes = value
        }
        searchMoves?.let(params.searchMoves::addAll)
    }
}
