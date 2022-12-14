package vn.com.oaivu.cash

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.*
import kotlin.math.min


class Classifier(assetManager: AssetManager, modelPath: String, labelPath: String, private val inputSize: Int) {
    private var interpreter: Interpreter
    private var lableList: List<String>
    private val pixelSize: Int = 3
    private val imageMean = 0
    private val imageStd = 255.0f
    private val maxResult = 10
    private val threshHold = 0.7f

    data class Recognition(
        var id: String = "",
        var title: String = "",
        var confidence: kotlin.Float = 0F
    )  {
        override fun toString(): String {
            return ""
        }
    }

    init {
        val options = Interpreter.Options()
        options.setNumThreads(5)
        options.setUseNNAPI(true) // Enable hardware acceleration on devices when available
        interpreter = Interpreter(loadModelFile(assetManager, modelPath), options)
        lableList = loadLabelList(assetManager, labelPath)
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, modelPath: String): MappedByteBuffer {
        // Get the file descriptor of the model file
        val fileDescriptor = assetManager.openFd(modelPath)

        // Open the input stream
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)

        // Read the file channels along with its offset as follows
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength

        // Load the TFLite model and return as a mapped byte buffer
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    @Throws(IOException::class)
    private fun loadLabelList(assetManager: AssetManager, labelPath: String): List<String> {
        return assetManager.open(labelPath).bufferedReader().useLines { it.toList() }

    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * pixelSize)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)

        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var pixel = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val input = intValues[pixel++]

                byteBuffer.putFloat((((input.shr(16)  and 0xFF) - imageMean) / imageStd))
                byteBuffer.putFloat((((input.shr(8) and 0xFF) - imageMean) / imageStd))
                byteBuffer.putFloat((((input and 0xFF) - imageMean) / imageStd))
            }
        }
        return byteBuffer
    }

    /**
     * Returns the result after running the recognition with the help of interpreter
     * on the passed bitmap
     */
    fun recognizeImage(bitmap: Bitmap): List<Recognition> {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false)
        val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)
        val result = Array(1) { FloatArray(lableList.size) }
        interpreter.run(byteBuffer, result)
        return getSortedResult(result)
    }


    private fun getSortedResult(labelProbArray: Array<FloatArray>): List<Recognition> {
        Log.d("Classifier", "List Size:(%d, %d, %d)".format(labelProbArray.size,labelProbArray[0].size,lableList.size))
        Log.d("Classifier", "Probability List: (%f, %f, %f, %f, %f, %f, %f, %f, %f)".format(labelProbArray[0][0],
            labelProbArray[0][1], labelProbArray[0][2], labelProbArray[0][3], labelProbArray[0][4], labelProbArray[0][5],
        labelProbArray[0][6], labelProbArray[0][7], labelProbArray[0][8]))

        val pq = PriorityQueue(
            maxResult,
            Comparator<Recognition> {
                    (_, _, confidence1), (_, _, confidence2)
                -> confidence1.compareTo(confidence2) * -1
            })

        for (i in lableList.indices) {
            val confidence = labelProbArray[0][i]
            if (confidence >= threshHold) {
                pq.add(Recognition("" + i,
                    if (lableList.size > i) lableList[i] else "Unknown", confidence)
                )
            }
        }
        Log.d("Classifier", "pqsize:(%d)".format(pq.size))

        val recognitions = ArrayList<Recognition>()
        val recognitionsSize = min(pq.size, maxResult)
        for (i in 0 until recognitionsSize) {
            recognitions.add(pq.poll())
        }
        Log.i("Classifier.kt", recognitions.size.toString())
        return recognitions
    }

    fun close() {
        interpreter.close()
    }
}