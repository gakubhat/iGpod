package com.igeeta.igpod.logic.utils.data

data class GitHubUser(
    val login: String,
    val name: String?,
    val avatar: Int,
    val contributed: Int
)