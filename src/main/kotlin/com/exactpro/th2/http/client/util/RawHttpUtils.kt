package com.exactpro.th2.http.client.util

import rawhttp.core.body.BodyReader

fun BodyReader.tryClose() = runCatching(this::close)