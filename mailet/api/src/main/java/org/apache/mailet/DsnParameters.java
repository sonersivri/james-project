/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.mailet;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.mail.internet.AddressException;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.core.MailAddress;

import com.github.fge.lambdas.Throwing;
import com.github.steveash.guavate.Guavate;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * Represents DSN parameters attached to the envelope of an Email transiting over SMTP
 *
 * See https://tools.ietf.org/html/rfc3461
 */
public class DsnParameters {
    public static final String NOTIFY_PARAMETER = "NOTIFY";
    public static final String RFC_822_PREFIX = "rfc822;";
    public static final String ORCPT_PARAMETER = "ORCPT";
    public static final String ENVID_PARAMETER = "ENVID";
    public static final String RET_PARAMETER = "RET";

    /**
     * RET parameter allow the sender to control which part of the bounced message should be returned to the sender.
     *
     * Either headers or full.
     *
     * https://tools.ietf.org/html/rfc3461#section-4.3
     */
    public enum Ret {
        FULL,
        HDRS;

        public static Optional<Ret> fromSMTPArgLine(Map<String, String> mailFromArgLine) {
            return Optional.ofNullable(mailFromArgLine.get(RET_PARAMETER))
                .map(input -> parse(input)
                    .orElseThrow(() -> new IllegalArgumentException(input + " is not a supported value for RET DSN parameter")));
        }

        public static Ret fromAttributeValue(AttributeValue<String> attributeValue) {
            return parse(attributeValue.value())
                .orElseThrow(() -> new IllegalArgumentException(attributeValue.value() + " is not a supported value for RET DSN parameter"));
        }

        public static AttributeValue<String> toAttributeValue(Ret value) {
            return AttributeValue.of(value.toString());
        }

        public static Optional<Ret> parse(String string) {
            Preconditions.checkNotNull(string);

            return Arrays.stream(Ret.values())
                .filter(value -> value.toString().equalsIgnoreCase(string))
                .findAny();
        }
    }

    /**
     * ENVID allow the sender to correlate a bounce with a submission.
     *
     * https://tools.ietf.org/html/rfc3461#section-4.4
     */
    public static class EnvId {
        public static Optional<EnvId> fromSMTPArgLine(Map<String, String> mailFromArgLine) {
            return Optional.ofNullable(mailFromArgLine.get(ENVID_PARAMETER))
                .map(EnvId::of);
        }

        public static EnvId fromAttributeValue(AttributeValue<String> attributeValue) {
            return of(attributeValue.value());
        }

        public static EnvId of(String value) {
            Preconditions.checkNotNull(value);

            return new EnvId(value);
        }

        private final String value;

        private EnvId(String value) {
            this.value = value;
        }

        public String asString() {
            return value;
        }

        public AttributeValue<String> toAttributeValue() {
            return AttributeValue.of(value);
        }

        @Override
        public final boolean equals(Object o) {
            if (o instanceof EnvId) {
                EnvId that = (EnvId) o;

                return Objects.equals(this.value, that.value);
            }
            return false;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .add("value", value)
                .toString();
        }
    }

    /**
     * NOTIFY controls in which situations a bounce should be emited for a given recipient.
     *
     * https://tools.ietf.org/html/rfc3461#section-4.1
     */
    public enum Notify {
        NEVER,
        SUCCESS,
        FAILURE,
        DELAY;

        public static EnumSet<Notify> fromAttributeValue(AttributeValue<String> attributeValue) {
            return parse(attributeValue.value());
        }

        public static AttributeValue<String> toAttributeValue(EnumSet<Notify> value) {
            return AttributeValue.of(Joiner.on(',').join(value));
        }

        public static EnumSet<Notify> parse(String input) {
            Preconditions.checkNotNull(input);

            return validate(EnumSet.copyOf(Splitter.on(",")
                .omitEmptyStrings()
                .trimResults()
                .splitToList(input)
                .stream()
                .map(string -> parseValue(string)
                    .orElseThrow(() -> new IllegalArgumentException(string + " could not be associated with any RCPT NOTIFY value")))
                .collect(Guavate.toImmutableList())));
        }

        public static Optional<Notify> parseValue(String input) {
            return Arrays.stream(Notify.values())
                .filter(value -> value.toString().equalsIgnoreCase(input))
                .findAny();
        }

        private static EnumSet<Notify> validate(EnumSet<Notify> input) {
            Preconditions.checkArgument(!input.contains(NEVER) || input.size() == 1,
                "RCPT Notify should not contain over values when containing never");

            return input;
        }
    }

    /**
     * Holds NOTIFY and ORCPT parameters for a specific recipient.
     */
    public static class RecipientDsnParameters {
        public static Optional<RecipientDsnParameters> fromSMTPArgLine(Map<String, String> rcptToArgLine) {
            Optional<EnumSet<Notify>> notifyParameter = Optional.ofNullable(rcptToArgLine.get(NOTIFY_PARAMETER))
                .map(Notify::parse);
            Optional<MailAddress> orcptParameter = Optional.ofNullable(rcptToArgLine.get(ORCPT_PARAMETER))
                .map(RecipientDsnParameters::parseOrcpt);

            return of(notifyParameter, orcptParameter);
        }

        public static Optional<RecipientDsnParameters> of(Optional<EnumSet<Notify>> notifyParameter, Optional<MailAddress> orcptParameter) {
            if (notifyParameter.isEmpty() && orcptParameter.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new RecipientDsnParameters(notifyParameter, orcptParameter));
        }

        public static RecipientDsnParameters of(EnumSet<Notify> notifyParameter, MailAddress orcptParameter) {
            return new RecipientDsnParameters(Optional.of(notifyParameter), Optional.of(orcptParameter));
        }

        public static RecipientDsnParameters of(MailAddress orcptParameter) {
            return new RecipientDsnParameters(Optional.empty(), Optional.of(orcptParameter));
        }

        public static RecipientDsnParameters of(EnumSet<Notify> notifyParameter) {
            return new RecipientDsnParameters(Optional.of(notifyParameter), Optional.empty());
        }

        private static MailAddress parseOrcpt(String input) {
            Preconditions.checkArgument(input.startsWith(RFC_822_PREFIX), "ORCPT must start with the rfc822 prefix");
            String addressPart = input.substring(RFC_822_PREFIX.length());
            try {
                return new MailAddress(addressPart);
            } catch (AddressException e) {
                throw new IllegalArgumentException(addressPart + " could not be parsed", e);
            }
        }

        private final Optional<EnumSet<Notify>> notifyParameter;
        private final Optional<MailAddress> orcptParameter;

        RecipientDsnParameters(Optional<EnumSet<Notify>> notifyParameter, Optional<MailAddress> orcptParameter) {
            this.notifyParameter = notifyParameter;
            this.orcptParameter = orcptParameter;
        }

        public Optional<EnumSet<Notify>> getNotifyParameter() {
            return notifyParameter;
        }

        public Optional<MailAddress> getOrcptParameter() {
            return orcptParameter;
        }

        @Override
        public final boolean equals(Object o) {
            if (o instanceof RecipientDsnParameters) {
                RecipientDsnParameters that = (RecipientDsnParameters) o;

                return Objects.equals(this.notifyParameter, that.notifyParameter)
                    && Objects.equals(this.orcptParameter, that.orcptParameter);
            }
            return false;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(notifyParameter, orcptParameter);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .add("notifyParameter", notifyParameter)
                .add("orcptParameter", orcptParameter)
                .toString();
        }
    }

    public static class DsnAttributeValues {
        private static final AttributeName ENVID_ATTRIBUTE_NAME = AttributeName.of("dsn-envid");
        private static final AttributeName RET_ATTRIBUTE_NAME = AttributeName.of("dsn-ret");
        private static final AttributeName NOTIFY_ATTRIBUTE_NAME = AttributeName.of("dsn-notify");
        private static final AttributeName ORCPT_ATTRIBUTE_NAME = AttributeName.of("dsn-orcpt");

        public static DsnAttributeValues extract(Map<AttributeName, Attribute> attributesMap) {
            Optional<AttributeValue<String>> envId = Optional.ofNullable(attributesMap.get(ENVID_ATTRIBUTE_NAME))
                .flatMap(attribute -> attribute.getValue().asAttributeValueOf(String.class));
            Optional<AttributeValue<String>> ret = Optional.ofNullable(attributesMap.get(RET_ATTRIBUTE_NAME))
                .flatMap(attribute -> attribute.getValue().asAttributeValueOf(String.class));
            Optional<AttributeValue<Map<String, AttributeValue<String>>>> notify =
                Optional.ofNullable(attributesMap.get(NOTIFY_ATTRIBUTE_NAME))
                    .flatMap(attribute -> attribute.getValue().asMapAttributeValueOf(String.class));
            Optional<AttributeValue<Map<String, AttributeValue<String>>>> orcpt =
                Optional.ofNullable(attributesMap.get(ORCPT_ATTRIBUTE_NAME))
                    .flatMap(attribute -> attribute.getValue().asMapAttributeValueOf(String.class));

            return new DsnAttributeValues(notify, orcpt, envId, ret);
        }

        private final Optional<AttributeValue<Map<String, AttributeValue<String>>>> notifyAttributeValue;
        private final Optional<AttributeValue<Map<String, AttributeValue<String>>>> orcptAttributeValue;
        private final Optional<AttributeValue<String>> envIdAttributeValue;
        private final Optional<AttributeValue<String>> retAttributeValue;

        public DsnAttributeValues(Optional<AttributeValue<Map<String, AttributeValue<String>>>> notifyAttributeValue, Optional<AttributeValue<Map<String, AttributeValue<String>>>> orcptAttributeValue, Optional<AttributeValue<String>> envIdAttributeValue, Optional<AttributeValue<String>> retAttributeValue) {
            this.notifyAttributeValue = notifyAttributeValue;
            this.orcptAttributeValue = orcptAttributeValue;
            this.envIdAttributeValue = envIdAttributeValue;
            this.retAttributeValue = retAttributeValue;
        }

        public Optional<AttributeValue<Map<String, AttributeValue<String>>>> getNotifyAttributeValue() {
            return notifyAttributeValue;
        }

        public Optional<AttributeValue<Map<String, AttributeValue<String>>>> getOrcptAttributeValue() {
            return orcptAttributeValue;
        }

        public Optional<AttributeValue<String>> getEnvIdAttributeValue() {
            return envIdAttributeValue;
        }

        public Optional<AttributeValue<String>> getRetAttributeValue() {
            return retAttributeValue;
        }

        public List<Attribute> asAttributes() {
            ImmutableList.Builder<Attribute> result = ImmutableList.builder();
            envIdAttributeValue.map(value -> new Attribute(ENVID_ATTRIBUTE_NAME, value)).ifPresent(result::add);
            retAttributeValue.map(value -> new Attribute(RET_ATTRIBUTE_NAME, value)).ifPresent(result::add);
            notifyAttributeValue.map(value -> new Attribute(NOTIFY_ATTRIBUTE_NAME, value)).ifPresent(result::add);
            orcptAttributeValue.map(value -> new Attribute(ORCPT_ATTRIBUTE_NAME, value)).ifPresent(result::add);
            return result.build();
        }
    }

    public static Optional<DsnParameters> of(Optional<EnvId> envIdParameter, Optional<Ret> retParameter, ImmutableMap<MailAddress, RecipientDsnParameters> rcptParameters) {
        if (envIdParameter.isEmpty() && retParameter.isEmpty() && rcptParameters.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DsnParameters(envIdParameter, retParameter, rcptParameters));
    }

    public static Optional<DsnParameters> fromAttributeValue(DsnAttributeValues dsnAttributeValues) {
        Optional<EnvId> envId = dsnAttributeValues.getEnvIdAttributeValue().map(EnvId::fromAttributeValue);
        Optional<Ret> ret = dsnAttributeValues.getRetAttributeValue().map(Ret::fromAttributeValue);
        Map<MailAddress, EnumSet<Notify>> notify = dsnAttributeValues.getNotifyAttributeValue()
            .map(mapAttributeValue -> mapAttributeValue.value()
                .entrySet()
                .stream()
                .map(Throwing.function(entry -> Pair.of(new MailAddress(entry.getKey()), Notify.fromAttributeValue(entry.getValue()))))
                .collect(Guavate.entriesToMap()))
            .orElse(ImmutableMap.of());
        Map<MailAddress, MailAddress> orcpt = dsnAttributeValues.getOrcptAttributeValue()
            .map(mapAttributeValue -> mapAttributeValue.value()
                .entrySet()
                .stream()
                .map(Throwing.function(entry -> Pair.of(new MailAddress(entry.getKey()), new MailAddress(entry.getValue().value()))))
                .collect(Guavate.toImmutableMap(
                    Pair::getKey,
                    Pair::getValue)))
            .orElse(ImmutableMap.of());
        ImmutableSet<MailAddress> rcpts = ImmutableSet.<MailAddress>builder()
            .addAll(notify.keySet())
            .addAll(orcpt.keySet())
            .build();
        ImmutableMap<MailAddress, RecipientDsnParameters> recipientDsnParameters = rcpts.stream()
            .map(rcpt -> Pair.of(rcpt, new RecipientDsnParameters(
                Optional.ofNullable(notify.get(rcpt)),
                Optional.ofNullable(orcpt.get(rcpt)))))
            .collect(Guavate.toImmutableMap(
                Pair::getKey,
                Pair::getValue));
        return of(envId, ret, recipientDsnParameters);
    }

    private final Optional<EnvId> envIdParameter;
    private final Optional<Ret> retParameter;
    private final ImmutableMap<MailAddress, RecipientDsnParameters> rcptParameters;

    DsnParameters(Optional<EnvId> envIdParameter, Optional<Ret> retParameter, ImmutableMap<MailAddress, RecipientDsnParameters> rcptParameters) {
        this.envIdParameter = envIdParameter;
        this.retParameter = retParameter;
        this.rcptParameters = rcptParameters;
    }

    public Optional<EnvId> getEnvIdParameter() {
        return envIdParameter;
    }

    public Optional<Ret> getRetParameter() {
        return retParameter;
    }

    public ImmutableMap<MailAddress, RecipientDsnParameters> getRcptParameters() {
        return rcptParameters;
    }

    public DsnAttributeValues toAttributes() {
        Optional<AttributeValue<String>> envIdAttributeValue = envIdParameter.map(EnvId::asString).map(AttributeValue::of);
        Optional<AttributeValue<String>> retAttributeValue = retParameter.map(Ret::toString).map(AttributeValue::of);
        Optional<AttributeValue<Map<String, AttributeValue<String>>>> notifyAttributeValue = AttributeValue.of(rcptParameters.entrySet().stream()
            .filter(entry -> entry.getValue().getNotifyParameter().isPresent())
            .map(entry -> Pair.of(entry.getKey().asString(),
                Notify.toAttributeValue(entry.getValue().getNotifyParameter().get())))
            .collect(Guavate.toImmutableMap(Pair::getKey, Pair::getValue)))
            .asMapAttributeValueOf(String.class);
        Optional<AttributeValue<Map<String, AttributeValue<String>>>> orcptAttributeValue = AttributeValue.of(rcptParameters.entrySet().stream()
            .filter(entry -> entry.getValue().getOrcptParameter().isPresent())
            .map(entry -> Pair.of(entry.getKey().asString(),
                AttributeValue.of(entry.getValue().getOrcptParameter().get().asString())))
            .collect(Guavate.toImmutableMap(Pair::getKey, Pair::getValue)))
            .asMapAttributeValueOf(String.class);

        return new DsnAttributeValues(notifyAttributeValue, orcptAttributeValue, envIdAttributeValue, retAttributeValue);
    }

    @Override
    public final boolean equals(Object o) {
        if (o instanceof DsnParameters) {
            DsnParameters that = (DsnParameters) o;

            return Objects.equals(this.envIdParameter, that.envIdParameter)
                && Objects.equals(this.retParameter, that.retParameter)
                && Objects.equals(this.rcptParameters, that.rcptParameters);
        }
        return false;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(envIdParameter, retParameter, rcptParameters);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
            .add("envIdParameter", envIdParameter)
            .add("retParameter", retParameter)
            .add("rcptParameters", rcptParameters)
            .toString();
    }
}