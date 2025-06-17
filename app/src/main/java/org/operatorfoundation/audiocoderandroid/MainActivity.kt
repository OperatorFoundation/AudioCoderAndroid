package org.operatorfoundation.audiocoderandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.operatorfoundation.audiocoder.CJarInterface
import org.operatorfoundation.audiocoder.WSPRMessage
import org.operatorfoundation.audiocoderandroid.ui.theme.AudioCoderAndroidTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?)
    {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {
            AudioCoderAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }



    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier)
{
    val test = byteArrayOf(0x00, 0x10, 0x20)
    val msg: Array<WSPRMessage> = CJarInterface.WSPRDecodeFromPcm(test, 0.0, false)

    Text(
        text = "Hello $msg!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AudioCoderAndroidTheme {
        Greeting("Android")
    }
}