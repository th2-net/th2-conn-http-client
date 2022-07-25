/*
 * Copyright 2022-2022 Exactpro (Exactpro Systems Limited)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.exactpro.th2.http.client.dirty.handler.data.pointers

abstract class Pointer(var position: Int) {

    protected var modified: Boolean = false

    var expansion = 0
        protected set

    fun isModified() = this.modified

    open fun move(amount: Int) {
        position+=amount
    }

    open fun settle() {
        expansion = 0
        this.modified = false
    }

}

abstract class ValuePointer<T>(position: Int, value: T): Pointer(position) {
    private var _value: T = value

    var value: T
        get() = _value
        set(value) {
            expansion+=getDifAmount(_value, value)
            _value = value
            modified = true
        }

    protected abstract fun getDifAmount(target:T, against: T): Int
}

class StringPointer(position: Int, value: String): ValuePointer<String>(position, value) {
    override fun getDifAmount(target: String, against: String): Int = target.length-against.length
}

class IntPointer(position: Int, value: Int): ValuePointer<Int>(position, value) {
    override fun getDifAmount(target: Int, against: Int): Int = value.toString().length-against.toString().length
}