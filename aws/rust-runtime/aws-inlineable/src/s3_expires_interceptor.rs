/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_smithy_runtime_api::box_error::BoxError;
use aws_smithy_runtime_api::client::interceptors::context::BeforeDeserializationInterceptorContextMut;
use aws_smithy_runtime_api::client::interceptors::Intercept;
use aws_smithy_runtime_api::client::runtime_components::RuntimeComponents;
use aws_smithy_types::config_bag::ConfigBag;
use aws_smithy_types::date_time::{DateTime, Format};

/// An interceptor to implement custom parsing logic for S3's `Expires` header. This
/// intercaptor copies the value of the `Expires` header to a (synthetically added)
/// `ExpiresString` header. It also attempts to parse the header as an `HttpDate`, if
/// that parsing fails the header is removed so the `Expires` field in the final output
/// will be `None`.
#[derive(Debug)]
pub(crate) struct S3ExpiresInterceptor;
const EXPIRES: &str = "Expires";
const EXPIRES_STRING: &str = "ExpiresString";

impl Intercept for S3ExpiresInterceptor {
    fn name(&self) -> &'static str {
        "S3ExpiresInterceptor"
    }

    fn modify_before_deserialization(
        &self,
        context: &mut BeforeDeserializationInterceptorContextMut<'_>,
        _: &RuntimeComponents,
        _: &mut ConfigBag,
    ) -> Result<(), BoxError> {
        let headers = context.response_mut().headers_mut();

        if headers.contains_key(EXPIRES) {
            let expires_header = headers.get(EXPIRES).unwrap().to_string();

            // If the Expires header fails to parse to an HttpDate we remove the header so
            // it is parsed to None. We use HttpDate since that is the SEP defined default
            // if no other format is specified in the model.
            if DateTime::from_str(&expires_header, Format::HttpDate).is_err() {
                tracing::debug!(
                    "Failed to parse the header `{EXPIRES}` = \"{expires_header}\" as an HttpDate. The raw string value can found in `{EXPIRES_STRING}`."
                );
                headers.remove(EXPIRES);
            }

            // Regardless of parsing success we copy the value of the Expires header to the
            // ExpiresString header.
            headers.insert(EXPIRES_STRING, expires_header);
        }

        Ok(())
    }
}
