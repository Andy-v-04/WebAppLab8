package com.example.customer_api.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.customer_api.dto.CustomerRequestDTO;
import com.example.customer_api.dto.CustomerResponseDTO;
import com.example.customer_api.dto.CustomerUpdateDTO;
import com.example.customer_api.entity.Customer;
import com.example.customer_api.entity.CustomerStatus;
import com.example.customer_api.exception.DuplicateResourceException;
import com.example.customer_api.exception.ResourceNotFoundException;
import com.example.customer_api.repository.CustomerRepository;

@Service
@Transactional
public class CustomerServiceImpl implements CustomerService {
    
    private final CustomerRepository customerRepository;
    
    @Autowired
    public CustomerServiceImpl(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }
    
    @Override
    public Page<CustomerResponseDTO> getAllCustomers(int page, int size, String sortBy, String sortDir) {
        Sort sort = sortDir.equalsIgnoreCase(Sort.Direction.ASC.name())
                ? Sort.by(sortBy).ascending()
                : Sort.by(sortBy).descending();

        Pageable pageable = PageRequest.of(page, size, sort);
        
        Page<Customer> customersPage = customerRepository.findAll(pageable);

        return customersPage.map(this::convertToResponseDTO);
    }
    
    @Override
    public CustomerResponseDTO getCustomerById(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + id));
        return convertToResponseDTO(customer);
    }
    
    @Override
    public CustomerResponseDTO createCustomer(CustomerRequestDTO requestDTO) {
        // Check for duplicates
        if (customerRepository.existsByCustomerCode(requestDTO.getCustomerCode())) {
            throw new DuplicateResourceException("Customer code already exists: " + requestDTO.getCustomerCode());
        }
        
        if (customerRepository.existsByEmail(requestDTO.getEmail())) {
            throw new DuplicateResourceException("Email already exists: " + requestDTO.getEmail());
        }
        
        // Convert DTO to Entity
        Customer customer = convertToEntity(requestDTO);
        
        // Save to database
        Customer savedCustomer = customerRepository.save(customer);
        
        // Convert Entity to Response DTO
        return convertToResponseDTO(savedCustomer);
    }
    
    @Override
    public CustomerResponseDTO updateCustomer(Long id, CustomerRequestDTO requestDTO) {
        Customer existingCustomer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + id));
        
        // Check if email is being changed to an existing one
        if (!existingCustomer.getEmail().equals(requestDTO.getEmail()) 
            && customerRepository.existsByEmail(requestDTO.getEmail())) {
            throw new DuplicateResourceException("Email already exists: " + requestDTO.getEmail());
        }
        
        // Update fields
        existingCustomer.setFullName(requestDTO.getFullName());
        existingCustomer.setEmail(requestDTO.getEmail());
        existingCustomer.setPhone(requestDTO.getPhone());
        existingCustomer.setAddress(requestDTO.getAddress());
        
        // Don't update customerCode (immutable)
        
        Customer updatedCustomer = customerRepository.save(existingCustomer);
        return convertToResponseDTO(updatedCustomer);
    }
    
    @Override
    public void deleteCustomer(Long id) {
        if (!customerRepository.existsById(id)) {
            throw new ResourceNotFoundException("Customer not found with id: " + id);
        }
        customerRepository.deleteById(id);
    }
    
    @Override
    public List<CustomerResponseDTO> searchCustomers(String keyword) {
        return customerRepository.searchCustomers(keyword)
                .stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }
    
    @Override
    public List<CustomerResponseDTO> getCustomersByStatus(String statusStr) {
        try {
            // 1. Chuyển đổi String "ACTIVE" -> Enum CustomerStatus.ACTIVE
            CustomerStatus statusEnum = CustomerStatus.valueOf(statusStr.toUpperCase());
            
            // 2. Gọi Repository với Enum (lúc này Repository đã sửa ở Bước 1)
            return customerRepository.findByStatus(statusEnum)
                    .stream()
                    .map(this::convertToResponseDTO)
                    .collect(Collectors.toList());
                    
        } catch (IllegalArgumentException e) {
            // 3. Nếu user nhập linh tinh (VD: "VuiVe"), báo lỗi luôn
            throw new ResourceNotFoundException("Invalid status: " + statusStr);
        }
    }
    
    // Helper Methods for DTO Conversion
    
    private CustomerResponseDTO convertToResponseDTO(Customer customer) {
        CustomerResponseDTO dto = new CustomerResponseDTO();
        dto.setId(customer.getId());
        dto.setCustomerCode(customer.getCustomerCode());
        dto.setFullName(customer.getFullName());
        dto.setEmail(customer.getEmail());
        dto.setPhone(customer.getPhone());
        dto.setAddress(customer.getAddress());
        dto.setStatus(customer.getStatus().toString());
        dto.setCreatedAt(customer.getCreatedAt());
        return dto;
    }
    
    private Customer convertToEntity(CustomerRequestDTO dto) {
        Customer customer = new Customer();
        customer.setCustomerCode(dto.getCustomerCode());
        customer.setFullName(dto.getFullName());
        customer.setEmail(dto.getEmail());
        customer.setPhone(dto.getPhone());
        customer.setAddress(dto.getAddress());
        return customer;
    }

    @Override
    public List<CustomerResponseDTO> advancedSearch(String name, String email, String statusStr) {
        CustomerStatus statusEnum = null;
        
        // Logic: Chỉ convert sang Enum nếu chuỗi không rỗng
        if (statusStr != null && !statusStr.trim().isEmpty()) {
            try {
                statusEnum = CustomerStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                // Nếu user nhập status bậy bạ (VD: "HAPPY"), ta sẽ bỏ qua filter status
                statusEnum = null; 
            }
        }

        // Gọi Repository
        List<Customer> customers = customerRepository.advancedSearch(name, email, statusEnum);
        
        // Map sang DTO
        return customers.stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    @Override
    public CustomerResponseDTO partialUpdateCustomer(Long id, CustomerUpdateDTO updateDTO) {
        // Find customer, if not send error
        Customer existingCustomer = customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + id));

        // Update Full Name
        if (updateDTO.getFullName() != null) {
            existingCustomer.setFullName(updateDTO.getFullName());
        } 
        
        // Update Email (validate loop)
        if (updateDTO.getEmail() != null) {
            // if email different and new email exists in DB -> error
            if (!existingCustomer.getEmail().equals(updateDTO.getEmail())
                && customerRepository.existsByEmail(updateDTO.getEmail())) {
                throw new DuplicateResourceException("Email already exists: " + updateDTO.getEmail());
                }
                existingCustomer.setEmail(updateDTO.getEmail());
        }

        // Update phone
        if (updateDTO.getPhone() != null) {
            existingCustomer.setPhone(updateDTO.getPhone());
        }

        // Update Address
        if (updateDTO.getAddress() != null) {
            existingCustomer.setAddress(updateDTO.getAddress());
        }

        // Save and return
        Customer updatedCustomer = customerRepository.save(existingCustomer);
        return convertToResponseDTO(updatedCustomer);
    }
}
