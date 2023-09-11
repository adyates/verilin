package com.pandelyon.kotlin_verify.controller

import com.fasterxml.jackson.annotation.JsonView
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController;

@RestController
class DataIngressController {

    @GetMapping("/device/upload/{deviceId}")
    @JsonView(Ingress.UploadUrl::class)
    fun getUploadUrl(
        @PathVariable("deviceId") deviceId: String
    ): Ingress {
        // TODO: Make the necessarily call to S3 here and return the URL
        return Ingress()
    }
}

class Ingress(
    @JsonView(UploadUrl::class) val url: String = "https://verilin-mock-bucket.s3.amazonaws.com/mockfilename"
) {
    interface UploadUrl
}