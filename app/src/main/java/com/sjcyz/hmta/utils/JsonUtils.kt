package com.sjcyz.hmta.utils

import kotlinx.serialization.json.Json

val JsonWithUnknownKeys = Json { ignoreUnknownKeys = true }