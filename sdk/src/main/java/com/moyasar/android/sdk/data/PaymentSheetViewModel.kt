package com.moyasar.android.sdk.data

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MediatorLiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import android.content.res.Resources
import android.os.Parcelable
import android.text.Editable
import com.moyasar.android.sdk.PaymentConfig
import com.moyasar.android.sdk.PaymentResult
import com.moyasar.android.sdk.R
import com.moyasar.android.sdk.exceptions.ApiException
import com.moyasar.android.sdk.exceptions.PaymentSheetException
import com.moyasar.android.sdk.extensions.default
import com.moyasar.android.sdk.extensions.distinctUntilChanged
import com.moyasar.android.sdk.payment.PaymentService
import com.moyasar.android.sdk.payment.models.CardPaymentSource
import com.moyasar.android.sdk.payment.models.Payment
import com.moyasar.android.sdk.payment.models.PaymentRequest
import com.moyasar.android.sdk.payment.models.TokenRequest
import com.moyasar.android.sdk.util.CreditCardNetwork
import com.moyasar.android.sdk.util.getNetwork
import com.moyasar.android.sdk.util.isValidLuhnNumber
import com.moyasar.android.sdk.util.parseExpiry
import kotlinx.coroutines.*
import kotlinx.parcelize.Parcelize
import java.text.NumberFormat
import java.util.*
import kotlin.math.pow

class PaymentSheetViewModel(
    private val paymentConfig: PaymentConfig,
    private val resources: Resources
) : ViewModel() {
    private val _paymentService: PaymentService by lazy {
        PaymentService(paymentConfig.apiKey, paymentConfig.baseUrl)
    }

    private var ccOnChangeLocked = false
    private var ccExpiryOnChangeLocked = false

    private val _status = MutableLiveData<Status>().default(Status.Reset)
    private val _payment = MutableLiveData<Payment?>()
    private val _sheetResult = MutableLiveData<PaymentResult?>()
    private val _isFormValid = MediatorLiveData<Boolean>()

    val status: LiveData<Status> = _status
    internal val payment: LiveData<Payment?> = _payment
    internal val sheetResult: LiveData<PaymentResult?> = _sheetResult.distinctUntilChanged()

    private val name = MutableLiveData<String>().default("")
    private val number = MutableLiveData<String>().default("")
    private val cvc = MutableLiveData<String>().default("")
    private val expiry = MutableLiveData<String>().default("")

    private val nameValidator = LiveDataValidator(name).apply {
        val latinRegex = Regex("^[a-zA-Z\\-\\s]+\$")
        val nameRegex = Regex("^[a-zA-Z\\-]+\\s+?([a-zA-Z\\-]+\\s?)+\$")

        addRule("Name is required") { it.isNullOrBlank() }
        addRule("Name should only contain English alphabet") { !latinRegex.matches(it ?: "") }
        addRule("Both first and last names are required") { !nameRegex.matches(it ?: "") }
    }

    private val numberValidator = LiveDataValidator(number).apply {
        addRule("Credit card number is required") { it.isNullOrBlank() }
        addRule("Credit card number is invalid") { !isValidLuhnNumber(it ?: "") }
        addRule("Unsupported credit card network") {
            getNetwork(
                it ?: ""
            ) == CreditCardNetwork.Unknown
        }
    }

    private val cvcValidator = LiveDataValidator(cvc).apply {
        addRule("Security code is required") { it.isNullOrBlank() }
        addRule("Invalid security code") {
            when (getNetwork(number.value ?: "")) {
                CreditCardNetwork.Amex -> (it?.length ?: 0) < 4
                else -> (it?.length ?: 0) < 3
            }
        }
    }

    private val expiryValidator = LiveDataValidator(expiry).apply {
        addRule("Expiry date is required") { it.isNullOrBlank() }
        addRule("Invalid date") { parseExpiry(it ?: "")?.isInvalid() ?: true }
        addRule("Expired card") { parseExpiry(it ?: "")?.expired() ?: false }
    }

    private fun validateForm(): Boolean {
        val validators = listOf(nameValidator, numberValidator, cvcValidator, expiryValidator)
        return validators.all { it.isValid() }.also { _isFormValid.value = it }
    }

    private val cleanCardNumber: String
        get() = number.value!!.replace(" ", "")

    private val expiryMonth: String
        get() = parseExpiry(expiry.value ?: "")?.month.toString()

    private val expiryYear: String
        get() = parseExpiry(expiry.value ?: "")?.year.toString()

    val payLabel: String
        get() {
            val currency = Currency.getInstance(paymentConfig.currency)
            val formatter = NumberFormat.getCurrencyInstance()
            formatter.currency = currency
            formatter.minimumFractionDigits = currency.defaultFractionDigits

            val label = resources.getString(R.string.payBtnLabel)

            val amount = formatter.format(
                paymentConfig.amount / (10.0.pow(formatter.currency!!.defaultFractionDigits.toDouble()))
            )

            return "$label $amount"
        }

    val amountLabel: String
        get() {
            val currency = Currency.getInstance(paymentConfig.currency)
            val formatter = NumberFormat.getCurrencyInstance()
            formatter.currency = currency
            formatter.minimumFractionDigits = currency.defaultFractionDigits

            return formatter.format(
                paymentConfig.amount / (10.0.pow(formatter.currency!!.defaultFractionDigits.toDouble()))
            )
        }

    fun submit() {
        if (!validateForm()) {
            return
        }

        if (_status.value != Status.Reset) {
            return
        }

        _status.value = Status.SubmittingPayment

        if (paymentConfig.createSaveOnlyToken) {
            createSaveOnlyToken()
        } else {
            createPayment()
        }
    }

    private fun createPayment() {
        val request = PaymentRequest(
            paymentConfig.amount,
            paymentConfig.currency,
            paymentConfig.description,
            PaymentService.RETURN_URL,
            CardPaymentSource(
                name.value!!,
                cleanCardNumber,
                expiryMonth,
                expiryYear,
                cvc.value!!,
                if (paymentConfig.manual) "true" else "false",
                if (paymentConfig.saveCard) "true" else "false",
            ),
            paymentConfig.metadata ?: HashMap()
        )

        CoroutineScope(Job() + Dispatchers.Main)
            .launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val response = _paymentService.create(request)
                        RequestResult.Success(response)
                    } catch (e: ApiException) {
                        RequestResult.Failure(e)
                    } catch (e: Exception) {
                        RequestResult.Failure(e)
                    }
                }

                when (result) {
                    is RequestResult.Success -> {
                        _payment.value = result.payment

                        when (result.payment.status.lowercase()) {
                            "initiated" -> {
                                _status.value =
                                    Status.PaymentAuth3dSecure(result.payment.getCardTransactionUrl())
                            }

                            else -> {
                                _sheetResult.value = PaymentResult.Completed(result.payment)
                            }
                        }
                    }

                    is RequestResult.Failure -> {
                        _sheetResult.value = PaymentResult.Failed(result.e)
                    }
                }
            }
    }

    private fun createSaveOnlyToken() {
        val request = TokenRequest(
            name.value!!,
            cleanCardNumber,
            cvc.value!!,
            expiryMonth,
            expiryYear,
            true,
            "https://sdk.moyasar.com"
        )

        CoroutineScope(Job() + Dispatchers.Main).launch {
            _sheetResult.value = try {
                PaymentResult.CompletedToken(_paymentService.createToken(request))
            } catch (e: ApiException) {
                PaymentResult.Failed(e)
            } catch (e: Exception) {
                PaymentResult.Failed(e)
            }
        }
    }

    fun onPaymentAuthReturn(result: PaymentService.WebViewAuthResult) {
        when (result) {
            is PaymentService.WebViewAuthResult.Completed -> {
                if (result.id != _payment.value?.id) {
                    throw Exception("Got different ID from auth process ${result.id} instead of ${_payment.value?.id}")
                }

                val payment = _payment.value!!
                payment.apply {
                    status = result.status
                    source["message"] = result.message
                }

                _sheetResult.value = PaymentResult.Completed(payment)
            }

            is PaymentService.WebViewAuthResult.Failed -> {
                _sheetResult.value = PaymentResult.Failed(PaymentSheetException(result.error))
            }

            is PaymentService.WebViewAuthResult.Canceled -> {
                _sheetResult.value = PaymentResult.Canceled
            }
        }
    }

    fun creditCardTextChanged(textEdit: Editable) {
        if (ccOnChangeLocked) {
            return
        }

        ccOnChangeLocked = true

        val input = textEdit.toString().replace(" ", "")
        val formatted = StringBuilder()

        for ((current, char) in input.toCharArray().withIndex()) {
            if (current > 15) {
                break
            }

            if (current > 0 && current % 4 == 0) {
                formatted.append(' ')
            }

            formatted.append(char)
        }

        textEdit.replace(0, textEdit.length, formatted.toString())

        ccOnChangeLocked = false
    }

    fun expiryChanged(textEdit: Editable) {
        if (ccExpiryOnChangeLocked) {
            return
        }

        ccExpiryOnChangeLocked = true

        val input = textEdit.toString()
            .replace(" ", "")
            .replace("/", "")

        val formatted = StringBuilder()

        for ((current, char) in input.toCharArray().withIndex()) {
            if (current > 5) {
                break
            }

            if (current == 2) {
                formatted.append(" / ")
            }

            formatted.append(char)
        }

        textEdit.replace(0, textEdit.length, formatted.toString())

        ccExpiryOnChangeLocked = false
    }

    sealed class Status : Parcelable {
        @Parcelize
        data object Reset : Status()

        @Parcelize
        data object SubmittingPayment : Status()

        @Parcelize
        data class PaymentAuth3dSecure(val url: String) : Status()

        @Parcelize
        data class Failure(val e: Throwable) : Status()
    }

    internal sealed class RequestResult {
        data class Success(val payment: Payment) : RequestResult()
        data class Failure(val e: Exception) : RequestResult()
    }
}
