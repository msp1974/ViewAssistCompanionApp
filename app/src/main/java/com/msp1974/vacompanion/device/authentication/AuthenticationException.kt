package com.msp1974.vacompanion.device.authentication

class AuthenticationException: Exception {
    constructor() : super()
    constructor(
        message: String,
        httpCode: Int,
        errorBody: String?,
    ) : super("$message, httpCode: $httpCode, errorBody: $errorBody")
}