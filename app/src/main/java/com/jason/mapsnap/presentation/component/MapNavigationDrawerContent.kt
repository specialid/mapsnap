package com.jason.mapsnap.presentation.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 좌측 네비게이션 드로어 메뉴 — 각 항목 클릭 시 드로어를 닫는 책임은 호출부(콜백)에 위임 */
@Composable
fun MapNavigationDrawerContent(
    onMoveToCurrentLocation: () -> Unit,
    onOpenLoadRoute: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenInfo: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF1F1F23),
        drawerContentColor = Color.White
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "MapSnap",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 28.dp),
            color = Color(0x33FFFFFF)
        )
        Spacer(modifier = Modifier.height(12.dp))
        NavigationDrawerItem(
            label = { Text("현재 위치로 이동") },
            selected = false,
            onClick = onMoveToCurrentLocation,
            icon = { Icon(Icons.Default.MyLocation, contentDescription = null) },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedTextColor = Color.White,
                unselectedIconColor = Color.White
            ),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
        NavigationDrawerItem(
            label = { Text("저장된 경로") },
            selected = false,
            onClick = onOpenLoadRoute,
            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedTextColor = Color.White,
                unselectedIconColor = Color.White
            ),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
        NavigationDrawerItem(
            label = { Text("설정") },
            selected = false,
            onClick = onOpenSettings,
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedTextColor = Color.White,
                unselectedIconColor = Color.White
            ),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
        NavigationDrawerItem(
            label = { Text("도움말") },
            selected = false,
            onClick = onOpenHelp,
            icon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null) },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedTextColor = Color.White,
                unselectedIconColor = Color.White
            ),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
        NavigationDrawerItem(
            label = { Text("정보") },
            selected = false,
            onClick = onOpenInfo,
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            colors = NavigationDrawerItemDefaults.colors(
                unselectedContainerColor = Color.Transparent,
                unselectedTextColor = Color.White,
                unselectedIconColor = Color.White
            ),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
        )
    }
}
