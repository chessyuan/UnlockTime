package org.localwake.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF245BB9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EEFF),
    onPrimaryContainer = Color(0xFF133978),
    secondaryContainer = Color(0xFFE5EDFA),
    onSecondaryContainer = Color(0xFF253B58),
    background = Color(0xFFF2F2EF),
    onBackground = Color(0xFF171816),
    surface = Color.White,
    onSurface = Color(0xFF171816),
    onSurfaceVariant = Color(0xFF5F605A),
    surfaceVariant = Color(0xFFEDEEEA),
    surfaceContainer = Color(0xFFF4F5F2),
    surfaceContainerHigh = Color(0xFFF7F7F4),
    outline = Color(0xFF777A70),
    outlineVariant = Color(0xFFE3E3DE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF102E5C),
    primaryContainer = Color(0xFF1D406C),
    onPrimaryContainer = Color(0xFFD7E5FF),
    secondaryContainer = Color(0xFF293B53),
    onSecondaryContainer = Color(0xFFDCE8FA),
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFECEEF2),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFECEEF2),
    onSurfaceVariant = Color(0xFFB7BEC8),
    surfaceVariant = Color(0xFF242930),
    surfaceContainer = Color(0xFF1E2228),
    surfaceContainerHigh = Color(0xFF23272E),
    outline = Color(0xFF929AA5),
    outlineVariant = Color(0xFF363B43),
)

@Composable internal fun UnlockphoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

@Composable internal fun Section(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

@Composable internal fun Detail(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

@Composable internal fun SettingRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Detail(detail)
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
    }
}
