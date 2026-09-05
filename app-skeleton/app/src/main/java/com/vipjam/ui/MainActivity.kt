package com.vipjam.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vipjam.data.VipJamPrefs
import com.vipjam.service.VipJamService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        onMasterChange = { on ->
                            VipJamService.start(this, on)
                        },
                        onProfile = { profile ->
                            VipJamService.setProfile(this, profile)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    onMasterChange: (Boolean) -> Unit,
    onProfile: (String) -> Unit
) {
    var masterOn by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("VipJam", style = MaterialTheme.typography.headlineLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Master", modifier = Modifier.weight(1f))
            Switch(
                checked = masterOn,
                onCheckedChange = {
                    masterOn = it
                    onMasterChange(it)
                }
            )
        }
        Text("Profile")
        VipJamPrefs.Profiles.ALL.forEach { profile ->
            Button(onClick = { onProfile(profile) }) {
                Text(profile)
            }
        }
    }
}
