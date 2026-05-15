# Spring Cloud Gateway - Long-Running Endpoint Configuration

## Problem
- Bakong transaction check endpoint takes up to **5 minutes**
- Gateway circuit breaker/timeout cuts request after **10 seconds**
- Need to allow specific endpoint to run for 5 minutes

## Solution Overview

Configure **3 timeout layers** in Spring Cloud Gateway:

1. **HTTP Client Timeout** (Netty)
2. **Circuit Breaker Time Limiter** (Resilience4j)
3. **Route-Specific Timeout** (Gateway Route Config)

---

## Current Configuration Analysis

### ✅ Already Configured (Good!)

```yaml
resilience4j:
  timelimiter:
    instances:
      bakongTopUpCB:
        timeoutDuration: 300s  # 5 minutes ✅
        cancelRunningFuture: false
```

### ❌ Missing Configuration (Need to Add)

1. **HTTP Client Response Timeout** - Currently using default (30s)
2. **Route-Specific Metadata** - Not configured for per-route timeout

---

## Complete Solution

### Step 1: Add HTTP Client Configuration

Add this to `application.yml`:

```yaml
spring:
  cloud:
    gateway:
      httpclient:
        # Connection timeout (how long to wait for connection)
        connect-timeout: 5000  # 5 seconds
        # Response timeout (how long to wait for response)
        response-timeout: 310s  # 5 minutes + 10 seconds buffer
        # Connection pool settings
        pool:
          type: ELASTIC
          max-connections: 500
          max-idle-time: 30s
```

### Step 2: Update Bakong Route with Metadata

Update the `bakong-topup-service` route:

```yaml
routes:
  - id: bakong-topup-service
    uri: lb://user-service
    predicates:
      - Path=/api/wallets/top-up/bakong/**
    filters:
      - name: CircuitBreaker
        args:
          name: bakongTopUpCB
          fallbackUri: forward:/fallback/user-service
      - name: Retry
        args:
          retries: 0  # Don't retry long-running requests
          statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE
          methods: POST
    metadata:
      response-timeout: 310000  # 5 minutes + 10 seconds (in milliseconds)
      connect-timeout: 5000      # 5 seconds (in milliseconds)
```

### Step 3: Keep Resilience4j Configuration

Your existing config is correct:

```yaml
resilience4j:
  timelimiter:
    instances:
      bakongTopUpCB:
        timeoutDuration: 300s  # 5 minutes
        cancelRunningFuture: false  # Don't cancel on timeout
```

---

## Complete Updated application.yml

Here's the complete configuration with all changes:

```yaml
spring:
  application:
    name: gateway-service
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2000ms
  cloud:
    gateway:
      # ── HTTP Client Configuration ──────────────────────────────────────
      httpclient:
        connect-timeout: 5000      # 5 seconds to establish connection
        response-timeout: 310s     # 5 minutes + 10s buffer for response
        pool:
          type: ELASTIC
          max-connections: 500
          max-idle-time: 30s
      
      # ── Discovery Locator ──────────────────────────────────────────────
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      
      # ── Routes ─────────────────────────────────────────────────────────
      routes:
        # ── Bakong Top-Up (5-minute timeout for payment processing) ──────
        - id: bakong-topup-service
          uri: lb://user-service
          predicates:
            - Path=/api/wallets/top-up/bakong/**
          filters:
            - name: CircuitBreaker
              args:
                name: bakongTopUpCB
                fallbackUri: forward:/fallback/user-service
            - name: Retry
              args:
                retries: 0  # Don't retry long-running requests
                statuses: BAD_GATEWAY,SERVICE_UNAVAILABLE
                methods: POST
          metadata:
            response-timeout: 310000  # 5 min + 10s buffer (milliseconds)
            connect-timeout: 5000      # 5 seconds (milliseconds)
        
        # ... (rest of your routes remain the same)

# ── Resilience4j Configuration ────────────────────────────────────────────
resilience4j:
  circuitbreaker:
    instances:
      bakongTopUpCB:
        baseConfig: default
        failureRateThreshold: 60
        slowCallDurationThreshold: 300s  # 5 minutes
        minimumNumberOfCalls: 3  # Lower threshold for long requests
  
  timelimiter:
    instances:
      bakongTopUpCB:
        timeoutDuration: 300s  # 5 minutes
        cancelRunningFuture: false  # Don't cancel the request
```

---

## Timeout Hierarchy Explained

### 1. **Connect Timeout** (5 seconds)
- **What**: Time to establish TCP connection
- **Where**: `spring.cloud.gateway.httpclient.connect-timeout`
- **Purpose**: Fail fast if service is unreachable
- **Recommendation**: Keep short (5-10 seconds)

### 2. **Response Timeout** (310 seconds)
- **What**: Time to receive complete response
- **Where**: 
  - Global: `spring.cloud.gateway.httpclient.response-timeout`
  - Per-route: `metadata.response-timeout`
- **Purpose**: Maximum time for request/response cycle
- **Recommendation**: Set to expected max duration + buffer

### 3. **Circuit Breaker Time Limiter** (300 seconds)
- **What**: Resilience4j timeout wrapper
- **Where**: `resilience4j.timelimiter.instances.bakongTopUpCB.timeoutDuration`
- **Purpose**: Circuit breaker timeout control
- **Recommendation**: Match or slightly less than response timeout

### 4. **Slow Call Threshold** (300 seconds)
- **What**: Threshold for marking calls as "slow"
- **Where**: `resilience4j.circuitbreaker.instances.bakongTopUpCB.slowCallDurationThreshold`
- **Purpose**: Don't mark 5-minute calls as slow
- **Recommendation**: Match expected duration

---

## Priority Order (Which Timeout Fires First?)

```
1. Connect Timeout (5s)          ← Fires first if can't connect
2. Response Timeout (310s)       ← Fires if no response received
3. Time Limiter (300s)           ← Fires if circuit breaker timeout
4. Slow Call Threshold (300s)    ← Marks call as slow (doesn't fail)
```

**Important**: The **smallest timeout wins**. Make sure:
- Response Timeout ≥ Time Limiter Timeout
- Time Limiter Timeout ≥ Expected Duration

---

## Testing the Configuration

### Test 1: Verify Timeout Settings

```bash
# Start Gateway and check logs
curl -X POST http://localhost:8080/api/wallets/top-up/bakong/checking-transaction \
  -H "Content-Type: application/json" \
  -H "X-Wallet-Session: your-token" \
  -d '{"hash": "test-hash"}' \
  -v
```

**Expected**: Request should wait up to 5 minutes without timeout

### Test 2: Check Circuit Breaker Status

```bash
# Check circuit breaker health
curl http://localhost:8080/actuator/circuitbreakers

# Check specific circuit breaker
curl http://localhost:8080/actuator/circuitbreakerevents/bakongTopUpCB
```

### Test 3: Monitor Logs

Look for these log entries:

```
✅ Good:
[DEBUG] Response timeout: 310000ms
[DEBUG] Circuit breaker timeout: 300000ms
[INFO] Request completed in 245000ms

❌ Bad (means timeout is too short):
[ERROR] TimeoutException: Did not observe any item or terminal signal
[ERROR] CircuitBreaker 'bakongTopUpCB' is OPEN
```

---

## Production Recommendations

### ❌ Not Recommended: Synchronous Long-Running Requests

**Problems:**
- Ties up threads for 5 minutes
- Poor scalability (limited concurrent requests)
- Bad user experience (no progress feedback)
- Gateway timeout complexity

### ✅ Recommended: Async Pattern (Already Implemented!)

**Benefits:**
- Returns immediately (202 ACCEPTED)
- Client polls for status
- Better scalability
- Better UX with progress updates
- No gateway timeout issues

**You already have this implemented!** See:
- `BAKONG_CIRCUIT_BREAKER_FIX_INSTRUCTIONS.md`
- `backend/user-service/BAKONG_ASYNC_FIX.md`

---

## Configuration Comparison

### For Synchronous Approach (Current Question):

```yaml
# Gateway needs ALL these timeouts increased
spring.cloud.gateway.httpclient.response-timeout: 310s
metadata.response-timeout: 310000
resilience4j.timelimiter.timeoutDuration: 300s
resilience4j.circuitbreaker.slowCallDurationThreshold: 300s
```

### For Async Approach (Recommended):

```yaml
# Gateway can use normal timeouts (< 10s)
spring.cloud.gateway.httpclient.response-timeout: 30s
metadata.response-timeout: 30000
resilience4j.timelimiter.timeoutDuration: 10s
resilience4j.circuitbreaker.slowCallDurationThreshold: 5s
```

---

## Common Issues & Solutions

### Issue 1: Still Getting Timeout After 10 Seconds

**Cause**: Route metadata not applied

**Solution**: Make sure route ID matches exactly:
```yaml
routes:
  - id: bakong-topup-service  # Must match timelimiter instance name
```

### Issue 2: Circuit Breaker Opens After Few Requests

**Cause**: Slow call threshold too low

**Solution**: Increase slow call threshold:
```yaml
slowCallDurationThreshold: 300s  # Match expected duration
```

### Issue 3: Request Cancelled Mid-Flight

**Cause**: `cancelRunningFuture: true`

**Solution**: Set to false:
```yaml
cancelRunningFuture: false  # Don't cancel long-running requests
```

---

## Summary

### What You Need to Change:

1. ✅ Add `spring.cloud.gateway.httpclient.response-timeout: 310s`
2. ✅ Add `metadata.response-timeout: 310000` to Bakong route
3. ✅ Set `retries: 0` for Bakong route (don't retry 5-min requests)
4. ✅ Update `slowCallDurationThreshold: 300s` for bakongTopUpCB

### What's Already Correct:

1. ✅ `resilience4j.timelimiter.timeoutDuration: 300s`
2. ✅ `cancelRunningFuture: false`
3. ✅ Separate circuit breaker for Bakong (`bakongTopUpCB`)

### Best Practice:

**Use the async pattern you already implemented!** It's better than increasing timeouts.

---

## Files to Update

1. `backend/gateway-service/src/main/resources/application.yml` - Add HTTP client config
2. Test with curl commands above
3. Monitor logs for timeout issues
4. Consider switching to async pattern (already implemented!)
