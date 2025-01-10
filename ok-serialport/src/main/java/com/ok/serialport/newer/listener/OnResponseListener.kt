package com.ok.serialport.newer.listener

import com.ok.serialport.newer.data.Request
import com.ok.serialport.newer.data.Response
import java.io.IOException

/**
 *
 * @author Leyi
 * @date 2025/1/10 16:46
 */
interface OnResponseListener {

    fun onFailure(request: Request, e: IOException)

    fun onResponse(response: Response)
}