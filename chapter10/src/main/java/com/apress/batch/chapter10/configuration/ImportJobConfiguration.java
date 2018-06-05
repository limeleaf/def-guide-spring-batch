/**
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.apress.batch.chapter10.configuration;

import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;

import com.apress.batch.chapter10.batch.CustomerUpdateClassifier;
import com.apress.batch.chapter10.domain.CustomerAddressUpdate;
import com.apress.batch.chapter10.domain.CustomerContactUpdate;
import com.apress.batch.chapter10.domain.CustomerNameUpdate;
import com.apress.batch.chapter10.domain.CustomerUpdate;
import com.apress.batch.chapter10.domain.Transaction;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.FieldSetMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.LineTokenizer;
import org.springframework.batch.item.file.transform.PatternMatchingCompositeLineTokenizer;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.batch.item.xml.StaxEventItemReader;
import org.springframework.batch.item.xml.builder.StaxEventItemReaderBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.util.StringUtils;

/**
 * @author Michael Minella
 */
@Configuration
public class ImportJobConfiguration {

	@Autowired
	private JobBuilderFactory jobBuilderFactory;

	@Autowired
	private StepBuilderFactory stepBuilderFactory;

	@Bean
	public Job job() throws Exception {
		return this.jobBuilderFactory.get("importJob")
				.start(importCustomerUpdates())
				.next(importTransactions())
				.build();
	}

	@Bean
	public Step importCustomerUpdates() throws Exception {
		return this.stepBuilderFactory.get("importCustomerUpdates")
				.<CustomerUpdate, CustomerUpdate>chunk(100)
				.reader(customerUpdateItemReader(null))
				.writer(customerUpdateItemWriter())
				.build();
	}

	@Bean
	public LineTokenizer customerUpdatesLineTokenizer() throws Exception {
		DelimitedLineTokenizer recordType1 = new DelimitedLineTokenizer();

		recordType1.setNames("recordId", "customerId", "firstName", "middleName", "lastName");

		recordType1.afterPropertiesSet();

		DelimitedLineTokenizer recordType2 = new DelimitedLineTokenizer();

		recordType2.setNames("recordId", "customerId", "address1", "address2", "city", "state", "postalCode");

		recordType2.afterPropertiesSet();

		DelimitedLineTokenizer recordType3 = new DelimitedLineTokenizer();

		recordType3.setNames("recordId", "customerId", "emailAddress", "homePhone", "cellPhone", "workPhone", "notificationPreference");

		recordType3.afterPropertiesSet();

		Map<String, LineTokenizer> tokenizers = new HashMap<>(3);
		tokenizers.put("1*", recordType1);
		tokenizers.put("2*", recordType2);
		tokenizers.put("3*", recordType3);

		PatternMatchingCompositeLineTokenizer lineTokenizer = new PatternMatchingCompositeLineTokenizer();

		lineTokenizer.setTokenizers(tokenizers);

		return lineTokenizer;
	}

	@Bean
	public FieldSetMapper<CustomerUpdate> customerUpdateFieldSetMapper() {
		return fieldSet -> {
			switch (fieldSet.readInt("recordId")) {
				case 1: return new CustomerNameUpdate(fieldSet.readLong("customerId"),
						fieldSet.readString("firstName"),
						fieldSet.readString("middleName"),
						fieldSet.readString("lastName"));
				case 2: return new CustomerAddressUpdate(fieldSet.readLong("customerId"),
						fieldSet.readString("address1"),
						fieldSet.readString("address2"),
						fieldSet.readString("city"),
						fieldSet.readString("state"),
						fieldSet.readString("postalCode"));
				case 3:
					String rawPreference = fieldSet.readString("notificationPreference");

					Integer notificationPreference = null;

					if(StringUtils.hasText(rawPreference)) {
						notificationPreference = Integer.parseInt(rawPreference);
					}

					return new CustomerContactUpdate(fieldSet.readLong("customerId"),
						fieldSet.readString("emailAddress"),
						fieldSet.readString("homePhone"),
						fieldSet.readString("cellPhone"),
						fieldSet.readString("workPhone"),
							notificationPreference);
				default: throw new IllegalArgumentException("Invalid record type was found:" + fieldSet.readInt("recordId"));
			}
		};
	}

	@Bean
	@StepScope
	public FlatFileItemReader<CustomerUpdate> customerUpdateItemReader(@Value("#{jobParameters['customerUpdateFile']}") Resource inputFile) throws Exception {

		return new FlatFileItemReaderBuilder<CustomerUpdate>()
				.name("customerUpdateItemReader")
				.resource(inputFile)
				.lineTokenizer(customerUpdatesLineTokenizer())
				.fieldSetMapper(customerUpdateFieldSetMapper())
				.build();
	}

	@Bean
	public JdbcBatchItemWriter<CustomerUpdate> customerNameUpdateItemWriter(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<CustomerUpdate>()
				.beanMapped()
				.sql("UPDATE CUSTOMER SET FIRST_NAME = COALESCE(:firstName, FIRST_NAME), MIDDLE_NAME = COALESCE(:middleName, MIDDLE_NAME), LAST_NAME = COALESCE(:lastName, LAST_NAME) WHERE CUSTOMER_ID = :customerId")
				.dataSource(dataSource)
				.build();
	}

	@Bean
	public JdbcBatchItemWriter<CustomerUpdate> customerAddressUpdateItemWriter(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<CustomerUpdate>()
				.beanMapped()
				.sql("UPDATE CUSTOMER SET ADDRESS1 = COALESCE(:address1, ADDRESS1), ADDRESS2 = COALESCE(:address2, ADDRESS2), CITY = COALESCE(:city, CITY), STATE = COALESCE(:state, STATE), POSTAL_CODE = COALESCE(:postalCode, POSTAL_CODE) WHERE CUSTOMER_ID = :customerId")
				.dataSource(dataSource)
				.build();
	}

	@Bean
	public JdbcBatchItemWriter<CustomerUpdate> customerContactUpdateItemWriter(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<CustomerUpdate>()
				.beanMapped()
				.sql("UPDATE CUSTOMER SET EMAIL_ADDRESS = COALESCE(:emailAddress, EMAIL_ADDRESS), HOME_PHONE = COALESCE(:homePhone, HOME_PHONE), CELL_PHONE = COALESCE(:cellPhone, CELL_PHONE), WORK_PHONE = COALESCE(:workPhone, WORK_PHONE), NOTIFICATION_PREF = COALESCE(:notificationPreferences, NOTIFICATION_PREF) WHERE CUSTOMER_ID = :customerId")
				.dataSource(dataSource)
				.build();
	}

	@Bean
	public ClassifierCompositeItemWriter<CustomerUpdate> customerUpdateItemWriter() {

		CustomerUpdateClassifier classifier = new CustomerUpdateClassifier(customerNameUpdateItemWriter(null), customerAddressUpdateItemWriter(null), customerContactUpdateItemWriter(null));

		ClassifierCompositeItemWriter<CustomerUpdate> compositeItemWriter = new ClassifierCompositeItemWriter<>();

		compositeItemWriter.setClassifier(classifier);

		return compositeItemWriter;
	}

	@Bean
	public Step importTransactions() {
		return this.stepBuilderFactory.get("importTransactions")
				.<Transaction, Transaction>chunk(100)
				.reader(transactionItemReader(null))
				.writer(transactionItemWriter(null))
				.build();
	}

	@Bean
	@StepScope
	public StaxEventItemReader<Transaction> transactionItemReader(@Value("#{jobParameters['transactionFile']}") Resource transactionFile) {
		Jaxb2Marshaller unmarshaller = new Jaxb2Marshaller();
		unmarshaller.setClassesToBeBound(Transaction.class);

		return new StaxEventItemReaderBuilder<Transaction>()
				.name("fooReader")
				.resource(transactionFile)
				.addFragmentRootElements("transaction")
				.unmarshaller(unmarshaller)
				.build();
	}

	@Bean
	public JdbcBatchItemWriter<Transaction> transactionItemWriter(DataSource dataSource) {
		return new JdbcBatchItemWriterBuilder<Transaction>()
				.dataSource(dataSource)
				.sql("INSERT INTO TRANSACTION (TRANSACTION_ID, ACCOUNT_ACCOUNT_ID, CREDIT, DEBIT, TIMESTAMP) VALUES (:transactionId, :accountId, :credit, :debit, :timestamp)")
				.beanMapped()
				.build();
	}

	@Bean
	public 

}
