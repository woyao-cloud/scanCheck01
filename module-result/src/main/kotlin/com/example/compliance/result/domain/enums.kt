package com.example.compliance.result.domain

/** 基线 spec §4.8 统一枚举：finding 生命周期状态。finding.status 为唯一权威。 */
enum class FindingStatus { NEW, CONFIRMED, ASSIGNED, FIXING, FIXED, RECHECKING, CLOSED, IGNORED, FALSE_POSITIVE, ACCEPTED_RISK, WAIVED }
