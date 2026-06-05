package com.jason.mapsnap.presentation.map

sealed interface MapSideEffect {
    data class ShowToast(val message: String) : MapSideEffect
    data object RequestLocationPermission : MapSideEffect
    data object ShowRewardedAd : MapSideEffect
}
