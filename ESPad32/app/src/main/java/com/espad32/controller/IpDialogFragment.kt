package com.espad32.controller

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.widget.EditText
import androidx.fragment.app.DialogFragment

class IpDialogFragment : DialogFragment() {

    companion object {
        fun newInstance(currentIp: String, onIpSelected: (String) -> Unit): IpDialogFragment {
            return IpDialogFragment().also { frag ->
                frag.currentIp = currentIp
                frag.callback = onIpSelected
            }
        }
    }

    var currentIp: String = "192.168.4.1"
    var callback: ((String) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val editText = EditText(requireContext()).apply {
            setText(currentIp)
            hint = "e.g. 192.168.4.1 or 192.168.2.166"
            setPadding(48, 32, 48, 32)
        }

        return AlertDialog.Builder(requireContext())
            .setTitle("Connect to Car")
            .setMessage("Enter the car's IP address.\nAP mode: 192.168.4.1\nRouter mode: check router for IP")
            .setView(editText)
            .setPositiveButton("Connect") { _, _ ->
                val ip = editText.text.toString().trim()
                if (ip.isNotEmpty()) callback?.invoke(ip)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}
