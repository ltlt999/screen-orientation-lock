package com.orientlock.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 内存版 DataStore，供 Repository 测试用。
 *
 * 与真实实现的行为一致：每次 [updateData] 都发射新值；
 * 变换后值相等时真实 DataStore 会跳过写入，这里也照样发射——
 * 测试若依赖「没有多余发射」需自行判断，本类不模拟那一步优化。
 */
class FakeDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {

    private val state = MutableStateFlow(initial)

    /** 真实 DataStore.edit 是单写者 actor；并发 transform 会被串行化，逐个看到前一个的结果 */
    private val writeMutex = Mutex()

    override val data: Flow<Preferences> = state

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = writeMutex.withLock {
        val updated = transform(state.value)
        state.value = updated
        updated
    }

    /** 测试用：把某个 key 强制写成越界值，验证读取时的收敛 */
    fun putRaw(key: Preferences.Key<Int>, value: Int) {
        val mutable = state.value.toMutablePreferences()
        mutable[key] = value
        state.value = mutable.toPreferences()
    }
}
