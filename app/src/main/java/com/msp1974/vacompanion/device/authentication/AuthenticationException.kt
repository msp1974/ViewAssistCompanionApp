package com.msp1974.vacompanion.device.authentication

class AuthenticationException: Exception {
    val httpCode: Int
    val errorBody: String?

    constructor() : super() {
        httpCode = 0
        errorBody = null
    }

    constructor(
        message: String,
        httpCode: Int,
        errorBody: String?,
    ) : super("$message, httpCode: $httpCode, errorBody: $errorBody") {
        this.httpCode = httpCode
        this.errorBody = errorBody
    }
}