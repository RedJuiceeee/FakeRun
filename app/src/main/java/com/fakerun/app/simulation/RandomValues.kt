package com.fakerun.app.simulation

import kotlin.math.*

object RandomValues {
    // 无可变随机流：求解次数、回调频率和暂停均不会改变同一索引的随机值。
    fun unit(seed: Long, stream: Long, index: Long): Double {
        var bits = seed xor (stream* -7046029254386353131L) xor (index* -4658895280553007687L)
        bits = (bits xor (bits ushr 30))* -4658895280553007687L
        bits = (bits xor (bits ushr 27))* -7723592293110705685L
        bits = bits xor (bits ushr 31)
        return (bits ushr 11).toDouble()/9007199254740992.0
    }
    fun smoothStep5(u: Double): Double = u*u*u*(10+u*(-15+6*u))
}
