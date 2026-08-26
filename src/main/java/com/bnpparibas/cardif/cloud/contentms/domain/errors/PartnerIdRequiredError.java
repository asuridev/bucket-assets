package com.bnpparibas.cardif.cloud.contentms.domain.errors;

/**
 * No llego partnerId ni en el JSON ni en el header _p, y la clave del objeto lo exige.
 */
public class PartnerIdRequiredError extends BadRequestException {

    public static final String CODE = "PARTNER_ID_REQUIRED";

    public PartnerIdRequiredError(String message) {
        super(message, CODE);
    }
}
