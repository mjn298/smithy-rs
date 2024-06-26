/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::Arc;

use aws_config::{identity::IdentityCache, BehaviorVersion, Region};
use aws_credential_types::{
    provider::{future::ProvideCredentials as ProvideCredentialsFuture, ProvideCredentials},
    Credentials,
};
use aws_sdk_s3::Client;
use aws_smithy_runtime::client::http::test_util::infallible_client_fn;

// NOTE: These tests are _not_ S3 specific and would apply to any AWS SDK but due to the need to consume `aws-config`
// (which depends on relocated runtime crates) we can't make this an `awsSdkIntegrationTest(..)`.

#[tokio::test]
async fn test_identity_cache_reused_by_default() {
    let http_client =
        infallible_client_fn(|_req| http::Response::builder().status(200).body("OK!").unwrap());

    let provider = TestCredProvider::new();
    let cache = IdentityCache::lazy().build();
    let config = aws_config::defaults(BehaviorVersion::latest())
        .http_client(http_client)
        .credentials_provider(provider.clone())
        // TODO(rfc-43) - remove adding a cache when this is the new default
        .identity_cache(cache)
        .region(Region::new("us-west-2"))
        .load()
        .await;

    let c1 = Client::new(&config);
    let _ = c1.list_buckets().send().await;
    assert_eq!(1, provider.invoke_count.load(Ordering::SeqCst));

    let c2 = Client::new(&config);
    let _ = c2.list_buckets().send().await;
    assert_eq!(1, provider.invoke_count.load(Ordering::SeqCst));
}

// TODO(rfc-43) - add no_identity_cache() to ConfigLoader and re-enable test
// #[tokio::test]
// async fn test_identity_cache_explicit_unset() {
//     let http_client =
//         infallible_client_fn(|_req| http::Response::builder().status(200).body("OK!").unwrap());
//
//     let provider = TestCredProvider::new();
//
//     let config = aws_config::defaults(BehaviorVersion::latest())
//         .no_identity_cache()
//         .http_client(http_client)
//         .credentials_provider(provider.clone())
//         .region(Region::new("us-west-2"))
//         .load()
//         .await;
//
//     let c1 = Client::new(&config);
//     let _ = c1.list_buckets().send().await;
//     assert_eq!(1, provider.invoke_count.load(Ordering::SeqCst));
//
//     let c2 = Client::new(&config);
//     let _ = c2.list_buckets().send().await;
//     assert_eq!(2, provider.invoke_count.load(Ordering::SeqCst));
// }

#[tokio::test]
async fn test_identity_cache_ga_behavior_version() {
    let http_client =
        infallible_client_fn(|_req| http::Response::builder().status(200).body("OK!").unwrap());

    let provider = TestCredProvider::new();

    // no cache is defined in this behavior version by default so each client should get their own
    let config = aws_config::defaults(BehaviorVersion::v2023_11_09())
        .http_client(http_client)
        .credentials_provider(provider.clone())
        .region(Region::new("us-west-2"))
        .load()
        .await;

    let c1 = Client::new(&config);
    let _ = c1.list_buckets().send().await;
    assert_eq!(1, provider.invoke_count.load(Ordering::SeqCst));

    let c2 = Client::new(&config);
    let _ = c2.list_buckets().send().await;
    assert_eq!(2, provider.invoke_count.load(Ordering::SeqCst));
}

#[derive(Clone, Debug)]
struct TestCredProvider {
    invoke_count: Arc<AtomicI32>,
    creds: Credentials,
}

impl TestCredProvider {
    fn new() -> Self {
        TestCredProvider {
            invoke_count: Arc::new(AtomicI32::default()),
            creds: Credentials::for_tests(),
        }
    }
}

impl ProvideCredentials for TestCredProvider {
    fn provide_credentials<'a>(
        &'a self,
    ) -> aws_credential_types::provider::future::ProvideCredentials<'a>
    where
        Self: 'a,
    {
        self.invoke_count.fetch_add(1, Ordering::SeqCst);
        ProvideCredentialsFuture::ready(Ok(self.creds.clone()))
    }
}
