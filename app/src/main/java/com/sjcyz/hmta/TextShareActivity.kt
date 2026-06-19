package com.sjcyz.hmta

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.sjcyz.hmta.ui.theme.HmtaTheme

class TextShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val textContent = intent.getStringExtra("shared_text")
        val senderName = intent.getStringExtra("sender_name") ?: ""

        if (textContent.isNullOrEmpty()) {
            finish()
            return
        }

        setContent {
            HmtaTheme {
                TextShareDialog(
                    senderName = senderName,
                    textContent = textContent,
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
fun TextShareDialog(
    senderName: String,
    textContent: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    Dialog(onDismissRequest = onClose) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Sender info
                if (senderName.isNotEmpty()) {
                    Text(
                        text = context.getString(R.string.shared_from, senderName),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Selectable text content
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    colors = androidx.compose.material3.CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    SelectionContainer {
                        Text(
                            text = textContent,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons: Select All + Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(ClipboardManager::class.java)
                            cm.setPrimaryClip(
                                ClipData.newPlainText("Shared Text", textContent)
                            )
                            Toast.makeText(
                                context,
                                R.string.text_copied,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text(stringResource(R.string.select_all))
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }
}
