
package com.dailyloaf.model;

public class Customer {

    private final String customerId;
    private final String firstName;
    private final String surname;
    private final String whatsappNumber;
    private final String section;
    private final String houseNumber;
    private final String status;

    public Customer(
        String customerId,
        String firstName,
        String surname,
        String whatsappNumber,
        String section,
        String houseNumber,
        String status
    ) {
        this.customerId     = customerId;
        this.firstName      = firstName;
        this.surname        = surname;
        this.whatsappNumber = whatsappNumber;
        this.section        = section;
        this.houseNumber    = houseNumber;
        this.status         = status;
    }

    public String getCustomerId()     { return customerId; }
    public String getFirstName()      { return firstName; }
    public String getSurname()        { return surname; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public String getSection()        { return section; }
    public String getHouseNumber()    { return houseNumber; }
    public String getStatus()         { return status; }

    public String getFullName() {
        return firstName + " " + surname;
    }

    public boolean isActive() {
        return "Active".equalsIgnoreCase(status)
            || "Subscription".equalsIgnoreCase(status);
    }

    public boolean isSubscription() {
        return "Subscription".equalsIgnoreCase(status);
    }

    @Override
    public String toString() {
        return "Customer{id='" + customerId + "', name='" + getFullName() +
               "', number='" + whatsappNumber + "', status='" + status + "'}";
    }
}